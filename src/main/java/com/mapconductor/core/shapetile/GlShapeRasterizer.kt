package com.mapconductor.core.shapetile

import com.mapconductor.core.InternalMapConductorApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log

/**
 * Draws a [GlShapeScene] with OpenGL ES 3.0 off screen and reads the pixels
 * back: the GPU path of [ShapeTileRenderer], the counterpart of ios-sdk-core's
 * `MetalShapeRasterizer` and `MetalPathRasterizer`.
 *
 * - 4x MSAA (or what the GPU offers up to 4) gives the antialiasing; the stencil
 *   is multisampled too, so a covered edge is the share of its samples drawn.
 * - Premultiplied colours, blended source-over (`ONE, ONE_MINUS_SRC_ALPHA`), as an
 *   `ARGB_8888` bitmap holds them; the pixels come back premultiplied.
 * - y is flipped in the vertex shaders, so the first row `glReadPixels` returns
 *   is the top of the tile, as a bitmap's first row is.
 *
 * Tiles are asked for on several of the tile server's threads at once. A GL
 * context is current on one thread at a time, so there is a small pool of
 * contexts, each with its own programs and targets; a tile borrows one and
 * makes it current for the draw. Without GLES 3 (or when anything fails to set
 * up) [render] returns null and the caller draws on the CPU.
 */
@InternalMapConductorApi
internal object GlShapeRasterizer {
    private const val TAG = "GlShapeRasterizer"
    private const val MAX_CONTEXTS = 4
    private const val EGL_OPENGL_ES3_BIT = 0x40

    private val idle = ArrayDeque<Context>()
    private var created = 0
    private val lock = Object()

    @Volatile
    private var broken = false

    /** Whether GLES 3 drawing works here; false after the first failure to set it up. */
    val isAvailable: Boolean
        get() {
            if (broken) return false
            val context = acquire() ?: return false
            release(context)
            return true
        }

    /**
     * Draws [scene] and returns its pixels: a direct buffer of `pixels * pixels`
     * premultiplied RGBA, top row first. Null when the GPU cannot draw it.
     */
    fun render(scene: GlShapeScene): ByteBuffer? {
        if (scene.isEmpty) return null
        val context = acquire() ?: return null
        var ok = false
        try {
            val result = context.draw(scene)
            ok = result != null
            return result
        } catch (error: RuntimeException) {
            Log.w(TAG, "GPU tile failed; drawing on the CPU", error)
            return null
        } finally {
            if (ok) release(context) else context.destroy()
        }
    }

    /** Builds the shaders ahead of the first tile: about a tenth of a second on a phone. */
    fun prepare() {
        if (broken) return
        release(acquire() ?: return)
    }

    private fun acquire(): Context? {
        synchronized(lock) {
            if (broken) return null
            idle.pollFirst()?.let { context ->
                return if (context.makeCurrent()) context else null
            }
            if (created >= MAX_CONTEXTS) {
                while (idle.isEmpty()) lock.wait()
                return idle.pollFirst()?.takeIf { it.makeCurrent() }
            }
            created++
        }
        val context =
            try {
                Context.create()
            } catch (error: RuntimeException) {
                Log.w(TAG, "GLES 3 unavailable; shape tiles are drawn on the CPU", error)
                null
            }
        if (context == null) {
            synchronized(lock) {
                created--
                broken = true
                lock.notifyAll()
            }
        }
        return context
    }

    private fun release(context: Context) {
        context.releaseCurrent()
        synchronized(lock) {
            idle.addLast(context)
            lock.notifyAll()
        }
    }

    private fun Context.destroy() {
        destroyContext()
        synchronized(lock) {
            created--
            lock.notifyAll()
        }
    }

    private class Context private constructor(
        private val display: EGLDisplay,
        private val context: EGLContext,
        private val surface: EGLSurface,
    ) {
        private val samples: Int
        private val circleProgram: Int
        private val pathProgram: Int
        private val coverProgram: Int
        private val lineProgram: Int
        private val buffers = IntArray(8)
        private val pathExtentAt: Int
        private val coverExtentAt: Int
        private val circleExtentAt: Int
        private val lineExtentAt: Int
        /** Direct buffers for uploads, grown as needed and kept. */
        private var uploadBytes: ByteBuffer = ByteBuffer.allocateDirect(1 shl 16).order(ByteOrder.nativeOrder())
        private var targets = HashMap<Int, Targets>()

        private class Targets(
            val msaa: Int,
            val resolve: Int,
        )

        init {
            val max = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, max, 0)
            samples = minOf(4, max[0]).coerceAtLeast(1)
            circleProgram = program(CIRCLE_VERTEX, CIRCLE_FRAGMENT)
            pathProgram = program(PATH_VERTEX, PATH_FRAGMENT)
            coverProgram = program(COVER_VERTEX, COVER_FRAGMENT)
            lineProgram = program(LINE_VERTEX, COVER_FRAGMENT)
            pathExtentAt = GLES30.glGetUniformLocation(pathProgram, "extent")
            coverExtentAt = GLES30.glGetUniformLocation(coverProgram, "extent")
            circleExtentAt = GLES30.glGetUniformLocation(circleProgram, "extent")
            lineExtentAt = GLES30.glGetUniformLocation(lineProgram, "extent")
            GLES30.glGenBuffers(buffers.size, buffers, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[CORNERS])
            val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 1f, 1f, 0f, 1f)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, corners.size * 4, staticFloats(corners), GLES30.GL_STATIC_DRAW)
            glOk("setup")
        }

        fun makeCurrent(): Boolean = EGL14.eglMakeCurrent(display, surface, surface, context)

        fun releaseCurrent() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }

        fun destroyContext() {
            releaseCurrent()
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }

        fun draw(scene: GlShapeScene): ByteBuffer? {
            val pixels = scene.pixels
            val target = targets.getOrPut(pixels) { createTargets(pixels) }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.msaa)
            GLES30.glViewport(0, 0, pixels, pixels)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClearStencil(0)
            GLES30.glClearDepthf(1f)
            GLES30.glDepthMask(true)
            GLES30.glStencilMask(0xFF)
            GLES30.glColorMask(true, true, true, true)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glEnable(GLES30.GL_STENCIL_TEST)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_CULL_FACE)

            upload(FILL, scene.fill)
            upload(STROKE, scene.stroke)
            upload(FILL_COVERS, scene.fillCovers)
            upload(STROKE_COVERS, scene.strokeCovers)
            upload(CIRCLES, scene.circles)
            upload(LINES, scene.lineVertices)
            if (scene.lineColors.size > 0) {
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[LINE_COLORS])
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, scene.lineColors.size * 4, ints(scene.lineColors.data, scene.lineColors.size), GLES30.GL_STREAM_DRAW)
            }

            val extent = pixels.toFloat()
            for (batch in scene.batches) {
                if (!batch.fill.isEmpty()) {
                    GLES30.glColorMask(false, false, false, false)
                    GLES30.glStencilFunc(GLES30.GL_ALWAYS, 0, 0xFF)
                    // nonzero: front faces +1, back faces -1, both wrapping at 256.
                    GLES30.glStencilOpSeparate(GLES30.GL_FRONT, GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_INCR_WRAP)
                    GLES30.glStencilOpSeparate(GLES30.GL_BACK, GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_DECR_WRAP)
                    drawPath(FILL, batch.fill, extent)
                    GLES30.glColorMask(true, true, true, true)
                }
                if (!batch.fillCovers.isEmpty()) drawCovers(FILL_COVERS, batch.fillCovers, extent)
                if (!batch.circles.isEmpty()) drawCircles(batch.circles, extent)
                if (!batch.stroke.isEmpty()) {
                    GLES30.glColorMask(false, false, false, false)
                    GLES30.glStencilFunc(GLES30.GL_ALWAYS, 1, 0xFF)
                    GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_REPLACE)
                    drawPath(STROKE, batch.stroke, extent)
                    GLES30.glColorMask(true, true, true, true)
                }
                if (!batch.strokeCovers.isEmpty()) drawCovers(STROKE_COVERS, batch.strokeCovers, extent)
            }
            if (scene.lineCount > 0) drawLines(scene, extent)

            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, target.msaa)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, target.resolve)
            GLES30.glBlitFramebuffer(0, 0, pixels, pixels, 0, 0, pixels, pixels, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, target.resolve)
            val out = ByteBuffer.allocateDirect(pixels * pixels * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, pixels, pixels, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (!glOk("draw")) return null
            out.rewind()
            return out
        }

        private fun drawPath(
            buffer: Int,
            range: IntRange,
            extent: Float,
        ) {
            GLES30.glUseProgram(pathProgram)
            GLES30.glUniform1f(pathExtentAt, extent)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[buffer])
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, range.first, range.last - range.first + 1)
            GLES30.glDisableVertexAttribArray(0)
        }

        private fun drawCovers(
            buffer: Int,
            range: IntRange,
            extent: Float,
        ) {
            // Paint where the stencil is not 0, and put it back to 0 for the next shapes.
            GLES30.glStencilFunc(GLES30.GL_NOTEQUAL, 0, 0xFF)
            GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_ZERO)
            GLES30.glUseProgram(coverProgram)
            GLES30.glUniform1f(coverExtentAt, extent)
            bindCorners()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[buffer])
            val stride = GlShapeScene.COVER_FLOATS * 4
            val offset = range.first * stride
            instanced(1, 4, stride, offset)
            instanced(2, 4, stride, offset + 16)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, range.last - range.first + 1)
            disable(0, 1, 2)
        }

        private fun drawCircles(
            range: IntRange,
            extent: Float,
        ) {
            GLES30.glStencilFunc(GLES30.GL_ALWAYS, 0, 0xFF)
            GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_KEEP)
            GLES30.glUseProgram(circleProgram)
            GLES30.glUniform1f(circleExtentAt, extent)
            bindCorners()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[CIRCLES])
            val stride = GlShapeScene.CIRCLE_FLOATS * 4
            val offset = range.first * stride
            instanced(1, 4, stride, offset)
            instanced(2, 4, stride, offset + 16)
            instanced(3, 4, stride, offset + 32)
            instanced(4, 1, stride, offset + 48)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, range.last - range.first + 1)
            disable(0, 1, 2, 3, 4)
        }

        private fun drawLines(
            scene: GlShapeScene,
            extent: Float,
        ) {
            // Every line in one draw: each paints only nearer than what is there,
            // so once per line whatever its own joins and crossings overlap, and
            // each later line (nearer) over the earlier ones.
            GLES30.glDisable(GLES30.GL_STENCIL_TEST)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LESS)
            GLES30.glDepthMask(true)
            GLES30.glUseProgram(lineProgram)
            GLES30.glUniform1f(lineExtentAt, extent)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[LINES])
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[LINE_COLORS])
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 4, GLES30.GL_UNSIGNED_BYTE, true, 4, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, scene.lineVertices.size / 3)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glEnable(GLES30.GL_STENCIL_TEST)
        }

        private fun bindCorners() {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[CORNERS])
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
            GLES30.glVertexAttribDivisor(0, 0)
        }

        private fun instanced(
            location: Int,
            size: Int,
            stride: Int,
            offset: Int,
        ) {
            GLES30.glEnableVertexAttribArray(location)
            GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, stride, offset)
            GLES30.glVertexAttribDivisor(location, 1)
        }

        private fun disable(vararg locations: Int) {
            for (location in locations) {
                GLES30.glVertexAttribDivisor(location, 0)
                GLES30.glDisableVertexAttribArray(location)
            }
        }

        private fun upload(
            index: Int,
            values: GlShapeScene.Floats,
        ) {
            if (values.size == 0) return
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[index])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, values.size * 4, floats(values.data, values.size), GLES30.GL_STREAM_DRAW)
        }

        /** [values] in the shared upload buffer, grown when too small. */
        private fun floats(
            values: FloatArray,
            size: Int,
        ): FloatBuffer {
            val bytes = staging(size * 4)
            val buffer = bytes.asFloatBuffer()
            buffer.put(values, 0, size)
            buffer.rewind()
            return buffer
        }

        private fun ints(
            values: IntArray,
            size: Int,
        ): java.nio.IntBuffer {
            val bytes = staging(size * 4)
            val buffer = bytes.asIntBuffer()
            buffer.put(values, 0, size)
            buffer.rewind()
            return buffer
        }

        private fun staging(bytes: Int): ByteBuffer {
            if (uploadBytes.capacity() < bytes) {
                uploadBytes = ByteBuffer.allocateDirect(Integer.highestOneBit(bytes) shl 1).order(ByteOrder.nativeOrder())
            }
            uploadBytes.clear()
            return uploadBytes
        }

        private fun createTargets(pixels: Int): Targets {
            val renderbuffers = IntArray(3)
            GLES30.glGenRenderbuffers(3, renderbuffers, 0)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, renderbuffers[0])
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_RGBA8, pixels, pixels)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, renderbuffers[1])
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_DEPTH24_STENCIL8, pixels, pixels)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, renderbuffers[2])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, pixels, pixels)
            val framebuffers = IntArray(2)
            GLES30.glGenFramebuffers(2, framebuffers, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[0])
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, renderbuffers[0])
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_STENCIL_ATTACHMENT, GLES30.GL_RENDERBUFFER, renderbuffers[1])
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "MSAA framebuffer incomplete" }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[1])
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, renderbuffers[2])
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "resolve framebuffer incomplete" }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return Targets(framebuffers[0], framebuffers[1])
        }

        private fun program(
            vertex: String,
            fragment: String,
        ): Int {
            fun shader(
                type: Int,
                source: String,
            ): Int {
                val shader = GLES30.glCreateShader(type)
                GLES30.glShaderSource(shader, source)
                GLES30.glCompileShader(shader)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(shader) }
                return shader
            }
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, shader(GLES30.GL_VERTEX_SHADER, vertex))
            GLES30.glAttachShader(program, shader(GLES30.GL_FRAGMENT_SHADER, fragment))
            GLES30.glLinkProgram(program)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "program: " + GLES30.glGetProgramInfoLog(program) }
            return program
        }

        private fun glOk(where: String): Boolean {
            val error = GLES30.glGetError()
            if (error == GLES30.GL_NO_ERROR) return true
            Log.w(TAG, "GL error 0x${Integer.toHexString(error)} in $where")
            return false
        }

        companion object {
            const val FILL = 0
            const val STROKE = 1
            const val FILL_COVERS = 2
            const val STROKE_COVERS = 3
            const val CIRCLES = 4
            const val LINES = 5
            const val CORNERS = 6
            const val LINE_COLORS = 7

            fun create(): Context {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
                val version = IntArray(2)
                check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
                val attributes =
                    intArrayOf(
                        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_NONE,
                    )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no GLES 3 config" }
                val config = configs[0]
                val context =
                    EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
                val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface" }
                check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
                return Context(display, context, surface)
            }

            private fun staticFloats(values: FloatArray): FloatBuffer {
                val buffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                buffer.put(values)
                buffer.rewind()
                return buffer
            }

            private const val CIRCLE_VERTEX = """#version 300 es
layout(location = 0) in vec2 corner;
layout(location = 1) in vec4 geometry;
layout(location = 2) in vec4 fillColor;
layout(location = 3) in vec4 strokeColor;
layout(location = 4) in float kind;
uniform float extent;
out vec2 pixel;
flat out vec4 vGeometry;
flat out vec4 vFill;
flat out vec4 vStroke;
flat out float vKind;
void main() {
    vec2 lo;
    vec2 hi;
    if (kind > 0.5) {
        lo = geometry.xy;
        hi = lo + geometry.zw;
    } else {
        float reach = geometry.z + geometry.w * 0.5 + 1.0;
        lo = geometry.xy - reach;
        hi = geometry.xy + reach;
    }
    vec2 px = mix(lo, hi, corner);
    vec2 unit = px / extent;
    gl_Position = vec4(unit.x * 2.0 - 1.0, unit.y * 2.0 - 1.0, 0.0, 1.0);
    pixel = px;
    vGeometry = geometry;
    vFill = fillColor;
    vStroke = strokeColor;
    vKind = kind;
}
"""

            private const val CIRCLE_FRAGMENT = """#version 300 es
precision highp float;
in vec2 pixel;
flat in vec4 vGeometry;
flat in vec4 vFill;
flat in vec4 vStroke;
flat in float vKind;
out vec4 color;
void main() {
    if (vKind > 0.5) {
        color = vFill;
        return;
    }
    float d = length(pixel - vGeometry.xy);
    float radius = vGeometry.z;
    float halfWidth = vGeometry.w * 0.5;
    float fillCoverage = clamp(radius - d + 0.5, 0.0, 1.0);
    float strokeCoverage = clamp(radius + halfWidth - d + 0.5, 0.0, 1.0) - clamp(radius - halfWidth - d + 0.5, 0.0, 1.0);
    vec4 fill = vFill * fillCoverage;
    vec4 stroke = vStroke * strokeCoverage;
    color = stroke + fill * (1.0 - stroke.a);
}
"""

            private const val PATH_VERTEX = """#version 300 es
layout(location = 0) in vec2 position;
uniform float extent;
void main() {
    vec2 unit = position / extent;
    gl_Position = vec4(unit.x * 2.0 - 1.0, unit.y * 2.0 - 1.0, 0.0, 1.0);
}
"""

            private const val LINE_VERTEX = """#version 300 es
layout(location = 0) in vec3 position;
layout(location = 1) in vec4 lineColor;
uniform float extent;
flat out vec4 vColor;
void main() {
    vec2 unit = position.xy / extent;
    gl_Position = vec4(unit.x * 2.0 - 1.0, unit.y * 2.0 - 1.0, position.z * 2.0 - 1.0, 1.0);
    vColor = lineColor;
}
"""

            private const val COVER_VERTEX = """#version 300 es
layout(location = 0) in vec2 corner;
layout(location = 1) in vec4 rect;
layout(location = 2) in vec4 coverColor;
uniform float extent;
flat out vec4 vColor;
void main() {
    vec2 px = mix(rect.xy, rect.zw, corner);
    vec2 unit = px / extent;
    gl_Position = vec4(unit.x * 2.0 - 1.0, unit.y * 2.0 - 1.0, 0.0, 1.0);
    vColor = coverColor;
}
"""

            private const val PATH_FRAGMENT = """#version 300 es
precision mediump float;
uniform vec4 color;
out vec4 fragColor;
void main() {
    fragColor = color;
}
"""

            private const val COVER_FRAGMENT = """#version 300 es
precision mediump float;
flat in vec4 vColor;
out vec4 fragColor;
void main() {
    fragColor = vColor;
}
"""
        }
    }
}
