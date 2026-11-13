package com.mapconductor.core.shapetile

import com.mapconductor.core.InternalMapConductorApi
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What one shape tile draws on the GPU ([GlShapeRasterizer]): plain float
 * arrays, built here without touching GL so the building can be tested on the JVM.
 * The counterpart of ios-sdk-core's `MetalPathRasterizer.Scene` and
 * `PathSceneBuilder`; coordinates are tile pixels, y down.
 *
 * Three kinds of content, drawn as the CPU path ([android.graphics.Canvas]) draws them:
 * - **Circles** (and one-pixel dots): one instance each, fill and outline from
 *   the distance to the centre.
 * - **Polygons**: a nonzero fill by stencil-then-cover (a fan of triangles from
 *   one point per ring, +1 front / -1 back), then the outline as segment quads
 *   plus vertex discs written to the stencil and covered once -- a translucent
 *   outline is not painted twice where its joins overlap, as with `drawPath`.
 * - **Polylines**: each its own stencil number (1..255), drawn where the stencil
 *   is not that number and setting it, so a line is painted once and lines stack
 *   in order. GLES has no stencil export, so each line is its own draw call.
 *
 * Shapes are drawn in the order added. Circles of a circle tile go in one
 * instanced draw ([orderedCircles]), which keeps their order by itself; other
 * shapes are grouped into batches of shapes whose boxes do not overlap
 * (checked on an 8 px grid), where order does not show.
 */
@InternalMapConductorApi
internal class GlShapeScene(
    val pixels: Int,
    /**
     * Circles go into one ordered list and nothing else may be added: a
     * circle tile is mostly overlapping circles, which would split batches
     * into thousands. [addPolygon] then throws [NeedsBatches].
     */
    val orderedCircles: Boolean = false,
) {
    /** Thrown by [addPolygon] in [orderedCircles] mode: draw the tile again batched. */
    class NeedsBatches : RuntimeException(null, null, false, false)

    /** A growable float array. */
    class Floats(capacity: Int = 1024) {
        var data = FloatArray(capacity)
            private set
        var size = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(max(16, data.size * 2))
            data[size++] = value
        }

        fun add2(
            a: Float,
            b: Float,
        ) {
            if (size + 2 > data.size) data = data.copyOf(max(16, data.size * 2))
            data[size++] = a
            data[size++] = b
        }
    }

    /** Ranges of each array that a batch draws, as `[start, end)` element indices. */
    class Batch(
        val fill: IntRange,
        val fillCovers: IntRange,
        val circles: IntRange,
        val stroke: IntRange,
        val strokeCovers: IntRange,
    )

    /** Fill fans: `x, y` per vertex, triangles. */
    val fill = Floats()

    /** Polygon outlines: `x, y` per vertex, triangles. */
    val stroke = Floats()

    /** `left, top, right, bottom, r, g, b, a` (premultiplied) per cover. */
    val fillCovers = Floats(256)
    val strokeCovers = Floats(256)

    /**
     * [CIRCLE_FLOATS] per circle: `x, y, radius, strokeWidth, fill rgba,
     * stroke rgba, kind` -- kind 0 a circle, 1 a rectangle at `x, y` of
     * `radius` by `strokeWidth` (a pixel-sized dot).
     */
    val circles = Floats()
    val batches = ArrayList<Batch>()

    /** Polyline triangles: `x, y` per vertex. */
    val lineVertices = Floats()

    /** Per line: first vertex, vertex count, stencil number (1..255). */
    val lines = IntList()

    /** Per line: premultiplied `r, g, b, a`. */
    val lineColors = Floats(256)

    val isEmpty: Boolean
        get() = fillCovers.size == 0 && strokeCovers.size == 0 && circles.size == 0 && lineVertices.size == 0

    private val cell = 8
    private val cells = (pixels + cell - 1) / cell
    private val occupied = BooleanArray(cells * cells)
    private val touched = IntList()
    private var fillStart = 0
    private var fillCoverStart = 0
    private var circleStart = 0
    private var strokeStart = 0
    private var strokeCoverStart = 0
    private var lineCount = 0

    /** A circle (kind 0) or a pixel rectangle (kind 1); see [circles]. */
    fun addCircle(
        x: Float,
        y: Float,
        radius: Float,
        strokeWidth: Float,
        fill: Int,
        stroke: Int,
        kind: Int,
    ) {
        if (!orderedCircles) {
            val box =
                if (kind == 1) {
                    floatArrayOf(x, y, x + radius, y + strokeWidth)
                } else {
                    val reach = radius + strokeWidth / 2f + 1f
                    floatArrayOf(x - reach, y - reach, x + reach, y + reach)
                }
            if (!claim(box)) return
        }
        circles.add2(x, y)
        circles.add2(radius, strokeWidth)
        addColor(circles, fill)
        addColor(circles, stroke)
        circles.add(kind.toFloat())
    }

    /**
     * A polygon: [rings] in tile pixels (`x, y, ...`, the outline first) filled
     * nonzero with [fill], and the outline drawn [strokeWidth] wide with
     * [outline]; a colour of 0 is not drawn.
     */
    fun addPolygon(
        rings: List<FloatArray>,
        fill: Int,
        outline: Int,
        strokeWidth: Float,
    ) {
        if (orderedCircles) throw NeedsBatches()
        val first = rings.firstOrNull() ?: return
        if (first.size < 6) return
        val bounds = bounds(first)
        val box = if (outline != 0) inflate(bounds, strokeWidth / 2f) else bounds
        if (!claim(inflate(box, 1f))) return
        if (fill != 0) {
            val px = first[0]
            val py = first[1]
            for (ring in rings) {
                val n = ring.size / 2
                if (n < 3) continue
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    this.fill.add2(px, py)
                    this.fill.add2(ring[i * 2], ring[i * 2 + 1])
                    this.fill.add2(ring[j * 2], ring[j * 2 + 1])
                }
            }
            addCover(fillCovers, inflate(bounds, 1f), fill)
        }
        if (outline != 0) {
            strokeGeometry(first, closed = true, halfWidth = strokeWidth / 2f, out = stroke)
            addCover(strokeCovers, inflate(box, 1f), outline)
        }
    }

    /** A polyline in tile pixels (`x, y, ...`, two points or more). */
    fun addLine(
        points: FloatArray,
        color: Int,
        strokeWidth: Float,
    ) {
        if (points.size < 4) return
        val start = lineVertices.size / 2
        strokeGeometry(points, closed = false, halfWidth = strokeWidth / 2f, out = lineVertices)
        endLine(start, color)
    }

    /** A polyline that collapsed to one point: a disc of [radius] round [x], [y]. */
    fun addLineDot(
        x: Float,
        y: Float,
        radius: Float,
        color: Int,
    ) {
        val start = lineVertices.size / 2
        disc(x, y, radius, lineVertices)
        endLine(start, color)
    }

    /** Closes the last batch; the scene is complete. */
    fun finish(): GlShapeScene {
        close()
        return this
    }

    private fun endLine(
        start: Int,
        color: Int,
    ) {
        val count = lineVertices.size / 2 - start
        if (count == 0) return
        lines.add(start)
        lines.add(count)
        lines.add(lineCount % 255 + 1)
        lineCount++
        addColor(lineColors, color)
    }

    private fun close() {
        val batch =
            Batch(
                fill = fillStart / 2 until fill.size / 2,
                fillCovers = fillCoverStart / COVER_FLOATS until fillCovers.size / COVER_FLOATS,
                circles = circleStart / CIRCLE_FLOATS until circles.size / CIRCLE_FLOATS,
                stroke = strokeStart / 2 until stroke.size / 2,
                strokeCovers = strokeCoverStart / COVER_FLOATS until strokeCovers.size / COVER_FLOATS,
            )
        if (!batch.fillCovers.isEmpty() || !batch.circles.isEmpty() || !batch.strokeCovers.isEmpty()) batches.add(batch)
        for (k in 0 until touched.size) occupied[touched[k]] = false
        touched.clear()
        fillStart = fill.size
        fillCoverStart = fillCovers.size
        circleStart = circles.size
        strokeStart = stroke.size
        strokeCoverStart = strokeCovers.size
    }

    /**
     * Takes the cells of [box] (left, top, right, bottom) for the current batch,
     * closing it first when one of them is taken. False when the box is off the tile.
     */
    private fun claim(box: FloatArray): Boolean {
        if (box[2] < 0f || box[3] < 0f || box[0] > pixels || box[1] > pixels) return false
        val x0 = max(0, floor(box[0]).toInt() / cell)
        val y0 = max(0, floor(box[1]).toInt() / cell)
        val x1 = min(cells - 1, ceil(box[2]).toInt() / cell)
        val y1 = min(cells - 1, ceil(box[3]).toInt() / cell)
        if (x0 > x1 || y0 > y1) return false
        var overlaps = false
        loop@ for (y in y0..y1) {
            for (x in x0..x1) {
                if (occupied[y * cells + x]) {
                    overlaps = true
                    break@loop
                }
            }
        }
        if (overlaps) close()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val i = y * cells + x
                if (!occupied[i]) {
                    occupied[i] = true
                    touched.add(i)
                }
            }
        }
        return true
    }

    private fun addCover(
        to: Floats,
        box: FloatArray,
        color: Int,
    ) {
        to.add2(box[0], box[1])
        to.add2(box[2], box[3])
        addColor(to, color)
    }

    @InternalMapConductorApi
    companion object {
        const val COVER_FLOATS = 8
        const val CIRCLE_FLOATS = 13

        /** Premultiplied `r, g, b, a` of an ARGB colour. */
        fun addColor(
            to: Floats,
            argb: Int,
        ) {
            val a = (argb ushr 24) / 255f
            to.add2(((argb shr 16) and 0xFF) / 255f * a, ((argb shr 8) and 0xFF) / 255f * a)
            to.add2((argb and 0xFF) / 255f * a, a)
        }

        private fun bounds(points: FloatArray): FloatArray {
            val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var i = 0
            while (i < points.size) {
                box[0] = min(box[0], points[i])
                box[2] = max(box[2], points[i])
                box[1] = min(box[1], points[i + 1])
                box[3] = max(box[3], points[i + 1])
                i += 2
            }
            return box
        }

        private fun inflate(
            box: FloatArray,
            d: Float,
        ) = floatArrayOf(box[0] - d, box[1] - d, box[2] + d, box[3] + d)

        /** Unit circles (cos, sin pairs) for 6..32 segments, made once. */
        private val unitCircles: Array<FloatArray> =
            Array(33) { segments ->
                if (segments < 6) {
                    FloatArray(0)
                } else {
                    FloatArray(segments * 2) { i ->
                        val a = (i / 2).toDouble() / segments * 2 * PI
                        (if (i % 2 == 0) cos(a) else sin(a)).toFloat()
                    }
                }
            }

        /** Segments of a disc of [radius] px: enough for its round edge to look round. */
        private fun circleSegments(radius: Float): Int = (ceil(radius * 2f).toInt() + 4).coerceIn(6, 32)

        /**
         * A quad per segment and a disc per vertex (round joins and caps), as
         * triangles. They overlap; the stencil takes their union.
         */
        fun strokeGeometry(
            points: FloatArray,
            closed: Boolean,
            halfWidth: Float,
            out: Floats,
        ) {
            val n = points.size / 2
            if (n < 2 || halfWidth <= 0f) return
            val edges = if (closed) n else n - 1
            for (i in 0 until edges) {
                val j = (i + 1) % n
                val ax = points[i * 2]
                val ay = points[i * 2 + 1]
                val bx = points[j * 2]
                val by = points[j * 2 + 1]
                val dx = bx - ax
                val dy = by - ay
                val length = sqrt(dx * dx + dy * dy)
                if (length <= 0f) continue
                val nx = -dy / length * halfWidth
                val ny = dx / length * halfWidth
                out.add2(ax + nx, ay + ny)
                out.add2(ax - nx, ay - ny)
                out.add2(bx + nx, by + ny)
                out.add2(ax - nx, ay - ny)
                out.add2(bx - nx, by - ny)
                out.add2(bx + nx, by + ny)
            }
            for (i in 0 until n) disc(points[i * 2], points[i * 2 + 1], halfWidth, out)
        }

        /** A disc of [radius] round [cx], [cy], as a fan of triangles. */
        fun disc(
            cx: Float,
            cy: Float,
            radius: Float,
            out: Floats,
        ) {
            val unit = unitCircles[circleSegments(radius)]
            val segments = unit.size / 2
            var px = cx + unit[(segments - 1) * 2] * radius
            var py = cy + unit[(segments - 1) * 2 + 1] * radius
            for (k in 0 until segments) {
                val nx = cx + unit[k * 2] * radius
                val ny = cy + unit[k * 2 + 1] * radius
                out.add2(cx, cy)
                out.add2(px, py)
                out.add2(nx, ny)
                px = nx
                py = ny
            }
        }
    }
}

/** A growable int array. */
@InternalMapConductorApi
internal class IntList(
    capacity: Int = 256,
) {
    var data = IntArray(capacity)
        private set
    var size = 0
        private set

    operator fun get(index: Int): Int = data[index]

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(max(16, data.size * 2))
        data[size++] = value
    }

    fun clear() {
        size = 0
    }
}

/** World-pixel points (`x, y, ...`) as tile pixels: [originX], [originY] at 0, [r] pixels per unit. */
internal fun toTilePixels(
    points: DoubleArray,
    originX: Double,
    originY: Double,
    r: Float,
): FloatArray {
    val out = FloatArray(points.size)
    var i = 0
    while (i < points.size) {
        out[i] = ((points[i] - originX) * r).toFloat()
        out[i + 1] = ((points[i + 1] - originY) * r).toFloat()
        i += 2
    }
    return out
}
