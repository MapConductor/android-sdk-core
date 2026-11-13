package com.mapconductor.core.shapetile

import androidx.core.graphics.createBitmap
import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.tileserver.ScratchPool
import com.mapconductor.core.tileserver.TilePngEncoder
import com.mapconductor.core.tileserver.TileProviderInterface
import com.mapconductor.core.tileserver.TileRequest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.max
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache

/**
 * Draws a list of shapes into raster tiles, served by
 * [com.mapconductor.core.tileserver.LocalTileServer]: the part the polygon
 * tiles and the polyline tiles share. What a shape is and how it is drawn is
 * the [Painter]'s; everything else is here -- the grid index, a simplified copy
 * per zoom, the tile cache and its invalidation, the scratch bitmaps, PNG
 * encoding and cancellation. The counterpart of the "Shape tiles" section of
 * js-sdk-core's tile-sw.js and ios-sdk-core's `ShapeTileRenderer`.
 *
 * Geometry is in tile units (a tile is [tileSize] wide whatever its pixels);
 * a tile is drawn [pixelScale] pixels per unit, so a stroke of 1 dp is one
 * unit wide, as on the web where it is one CSS pixel, and stays that wide at
 * every zoom.
 *
 * Tiles are cached here without the version the map sees in their URL. An
 * in-place change ([update]) drops only the tiles that overlap what changed,
 * widened by the widest stroke; the map asks again for every tile because the
 * version moved, and the ones the change did not touch come straight back
 * from the cache.
 *
 * [renderTile] runs concurrently on the tile server's threads. Every change
 * replaces the model it reads, so a tile is drawn from one consistent list.
 */
@InternalMapConductorApi
internal class ShapeTileRenderer<T : Any>(
    val tileSize: Int = 256,
    cacheSizeBytes: Int = DEFAULT_CACHE_BYTES,
    private val pixelScale: () -> Float = { ResourceProvider.getDensity() },
    private val painter: Painter<T>,
    /** What a cancelled tile throws: a failure the map may retry, never an empty tile. */
    private val cancelled: () -> Exception = { TileRenderCancelled() },
) : TileProviderInterface {
    /** What a kind of shape is to the tiles. */
    @InternalMapConductorApi
    interface Painter<T : Any> {
        /** Web Mercator `minX, minY, maxX, maxY`. */
        fun bbox(item: T): DoubleArray

        /**
         * The widest the shape is drawn past its bounding box, as a stroke
         * width in tile units (dp): half of it reaches past each side. 0 when
         * nothing is drawn outside the box.
         */
        fun strokeWidth(item: T): Float

        val cacheSimplifiedShapes: Boolean get() = true
        val thinSubpixelOverlaps: Boolean get() = false

        fun isVisible(item: T): Boolean = true

        /** Pixel slot, -1 outside the tile, or -2 when the shape is not subpixel. */
        fun subpixelSlot(
            item: T,
            scale: Double,
            originX: Double,
            originY: Double,
            tileSize: Int,
        ): Int = -2

        /** The shape at a zoom whose world is [scale] units wide. Cached per zoom. */
        fun simplify(
            item: T,
            scale: Double,
        ): Any

        /**
         * Draws [shape] (from [simplify]) with its origin at [originX], [originY]
         * world units and [r] pixels per unit. True if anything was drawn.
         */
        fun draw(
            canvas: Canvas,
            item: T,
            shape: Any,
            originX: Double,
            originY: Double,
            r: Float,
            paints: Paints,
        ): Boolean

        /** Whether [record] can put this kind of shape in a [GlShapeScene]. */
        val drawsOnGpu: Boolean get() = false

        /**
         * Records [shape] into [scene] for the GPU, exactly as [draw] would draw
         * it on the canvas. True if anything was recorded.
         */
        fun record(
            scene: GlShapeScene,
            item: T,
            shape: Any,
            originX: Double,
            originY: Double,
            r: Float,
        ): Boolean = throw UnsupportedOperationException()

        /**
         * Records every shape of [candidates] at once, reading [storage]
         * directly instead of an item per shape; false when this painter or
         * storage has no such path (then the renderer records them one by one).
         * Must make the same choices as the one-by-one path.
         */
        fun recordAll(
            scene: GlShapeScene,
            storage: ShapeTileStorage<T>,
            candidates: IntArray,
            active: BitSet?,
            scale: Double,
            originX: Double,
            originY: Double,
            tileSize: Int,
            r: Float,
            checkCancelled: () -> Unit,
        ): Boolean = false
    }

    /** Paints and a path, one set per tile drawn. */
    @InternalMapConductorApi
    class Paints {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }

        /** Not anti-aliased: a polygon under a pixel is one whole pixel. */
        val dot = Paint().apply { style = Paint.Style.FILL }
        val path = Path()
    }

    @InternalMapConductorApi
    private class Simplified(
        val source: Any,
        val shape: Any,
    )

    /**
     * What tiles are drawn from. [perZoom] is shared by the models an
     * [update] derives from one [setItems]: an entry is used only while its
     * source is still the item at that index, so a changed item is
     * simplified again without clearing anything.
     */
    @InternalMapConductorApi
    private class Model<T : Any>(
        val storage: ShapeTileStorage<T>,
        val index: ShapeGridIndex,
        val maxStroke: Float,
        val perZoom: LinkedHashMap<Int, AtomicReferenceArray<Simplified?>>,
        val active: BitSet? = null,
    )

    @Volatile
    private var model: Model<T>? = null
    private val modelLock = Any()

    private fun acquireModel(): Model<T>? =
        synchronized(modelLock) {
            model?.also { it.storage.retain() }
        }

    /** Takes ownership of the replacement's storage reference. */
    private fun replaceModel(next: Model<T>?) =
        synchronized(modelLock) {
            val previous = model
            model = next
            previous?.storage?.release()
        }

    fun itemAt(index: Int): T? {
        val current = acquireModel() ?: return null
        return try {
            current.storage[index]
        } finally {
            current.storage.release()
        }
    }

    /**
     * Indices of the items whose bounding box meets the given one (Web Mercator,
     * [0, 1]), through the index the tiles use. For taps: the caller tests these
     * few against their states instead of reading every state.
     */
    fun candidates(
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
    ): IntArray {
        val m = acquireModel() ?: return IntArray(0)
        return try {
            m.index.query({ index -> if (m.active?.get(index) == false) null else m.storage.bbox(index) }, minX, minY, maxX, maxY)
        } finally {
            m.storage.release()
        }
    }

    /** The widest stroke drawn, in pixels of a [tileSize] tile. */
    val maxStroke: Float get() = model?.maxStroke ?: 0f

    /** Immutable membership snapshot; geometry and its spatial index stay shared. */
    fun setActiveItems(active: BitSet) {
        synchronized(modelLock) {
            val current = model ?: return
            current.storage.retain()
            replaceModel(Model(current.storage, current.index, current.maxStroke, current.perZoom, active))
        }
        synchronized(cacheLock) {
            generation.incrementAndGet()
            tileCache.evictAll()
        }
    }

    /** Bumped by every change, so a tile drawn before it is not cached after it. */
    private val generation = AtomicLong()

    private val tileCache =
        object : LruCache<Long, ByteArray>(cacheSizeBytes) {
            override fun sizeOf(
                key: Long,
                value: ByteArray,
            ): Int = value.size
        }
    private val cacheLock = Any()

    // Pools, not thread-locals: tiles are drawn on the map SDK's threads, which can
    // outlive this renderer (see ScratchPool) and would keep a tile bitmap each.
    private val scratchBitmaps = ScratchPool<Bitmap>()
    private val byteStreams = ScratchPool<ByteArrayOutputStream>()

    /** Replaces every shape. Draw order is list order. */
    fun setItems(items: List<T?>) {
        setStorage(MemoryShapeTileStorage(items, painter))
    }

    /** Builds the shared shape index without materialising file-backed geometry. Consumes [storage]. */
    fun setStorage(
        storage: ShapeTileStorage<T>,
        checkCancelled: () -> Unit = {},
    ) {
        val index =
            try {
                ShapeGridIndex
                    .build(storage.size) {
                        if (it % 4096 == 0) checkCancelled()
                        storage.bbox(it)
                    }.also { checkCancelled() }
            } catch (error: Throwable) {
                storage.release()
                throw error
            }
        replaceModel(Model(storage, index, storage.maxStroke, LinkedHashMap(8, 0.75f, true)))
        synchronized(cacheLock) {
            generation.incrementAndGet()
            tileCache.evictAll()
        }
    }

    /**
     * Replaces the shapes at the given indexes. Only the tiles that
     * overlapped one of them, before or after, are drawn again.
     */
    fun update(updates: List<Pair<Int, T?>>) {
        val current = acquireModel() ?: return
        var next: ShapeTileStorage<T>? = null
        try {
            val touched = ArrayList<DoubleArray>(updates.size * 2)
            var maxStroke = current.maxStroke
            var reindex = false
            for ((index, after) in updates) {
                if (index !in 0 until current.storage.size) continue
                val before = current.storage.bbox(index)
                val afterBox = after?.let(painter::bbox)
                before?.let(touched::add)
                afterBox?.let(touched::add)
                if (after != null) maxStroke = max(maxStroke, painter.strokeWidth(after))
                // A shape that moved needs the index rebuilt; a restyle does not.
                if (before == null || afterBox == null || !before.contentEquals(afterBox)) reindex = true
            }
            val replacement = current.storage.updated(updates)
            next = replacement
            val index = if (reindex) ShapeGridIndex.build(replacement.size, replacement::bbox) else current.index
            replaceModel(Model(replacement, index, maxStroke, current.perZoom, current.active))
            next = null
            synchronized(cacheLock) {
                generation.incrementAndGet()
                dropTilesIn(touched, maxStroke)
            }
        } finally {
            next?.release()
            current.storage.release()
        }
    }

    fun clear() {
        replaceModel(null)
        synchronized(cacheLock) {
            generation.incrementAndGet()
            tileCache.evictAll()
        }
    }

    override fun renderTile(request: TileRequest): ByteArray? = renderTile(request) { false }

    override fun renderTile(
        request: TileRequest,
        isCancelled: () -> Boolean,
    ): ByteArray? {
        val generationAtStart = generation.get()
        val m = acquireModel() ?: return null
        try {
            val z = request.z
            if (z !in 0..MAX_ZOOM) return null
            val n = 1 shl z
            val x = ((request.x % n) + n) % n
            val y = request.y
            if (y !in 0 until n) return null
            val ratio = request.pixelRatio.coerceIn(1, 3)
            val key = cacheKey(z, x, y, ratio)
            synchronized(cacheLock) { tileCache.get(key) }?.let { return it }

            val bytes =
                when (val gpu = drawOnGpu(m, x, y, z, ratio, isCancelled)) {
                    Gpu.Empty -> return null
                    is Gpu.Drawn -> encode(gpu.pixels, gpu.size)
                    Gpu.Unavailable -> {
                        val bitmap = draw(m, x, y, z, ratio, isCancelled) ?: return null
                        try {
                            encode(bitmap)
                        } finally {
                            releaseScratch(bitmap)
                        }
                    }
                }
            synchronized(cacheLock) {
                // The shapes may have changed while this tile was drawn.
                if (generation.get() == generationAtStart) tileCache.put(key, bytes)
            }
            return bytes
        } finally {
            m.storage.release()
        }
    }

    /** The tile as a bitmap, or null when nothing of any shape is in it. For tests and [renderTile]. */
    fun renderBitmap(
        x: Int,
        y: Int,
        z: Int,
        pixelRatio: Int = 1,
    ): Bitmap? {
        val current = acquireModel() ?: return null
        return try {
            when (val gpu = drawOnGpu(current, x, y, z, pixelRatio) { false }) {
                Gpu.Empty -> return null
                is Gpu.Drawn ->
                    return createBitmap(gpu.size, gpu.size).apply { copyPixelsFromBuffer(gpu.pixels) }
                Gpu.Unavailable -> Unit
            }
            draw(current, x, y, z, pixelRatio) { false }?.let { scratch ->
                try {
                    scratch.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    releaseScratch(scratch)
                }
            }
        } finally {
            current.storage.release()
        }
    }

    /** What [drawOnGpu] did. */
    private sealed class Gpu {
        /** Premultiplied RGBA, [size] pixels square, top row first. */
        class Drawn(
            val pixels: ByteBuffer,
            val size: Int,
        ) : Gpu()

        /** Nothing of any shape is in the tile. */
        object Empty : Gpu()

        /** No GPU drawing here (or this kind of shape): draw on the canvas. */
        object Unavailable : Gpu()
    }

    /**
     * The tile drawn by [GlShapeRasterizer]: the same shapes as [drawInto], in
     * the same order, recorded by the painter instead of drawn on a canvas.
     */
    private fun drawOnGpu(
        m: Model<T>,
        x: Int,
        y: Int,
        z: Int,
        pixelRatio: Int,
        isCancelled: () -> Boolean,
    ): Gpu {
        if (!useGpu || !painter.drawsOnGpu) return Gpu.Unavailable
        val scale = tileSize.toDouble() * (1L shl z).toDouble()
        val originX = x.toDouble() * tileSize
        val originY = y.toDouble() * tileSize
        val candidates = candidatesFor(m, originX, originY, scale)
        if (candidates.isEmpty()) return Gpu.Empty
        val r = pixelScale() * pixelRatio
        val px = (tileSize * r).roundToInt().coerceAtLeast(1)
        // A circle tile is one ordered list of circles; a circle drawn as its
        // 128-gon needs the batched scene instead.
        val scene =
            try {
                val ordered = GlShapeScene(px, orderedCircles = painter.thinSubpixelOverlaps)
                val checkCancelled = { if (isCancelled()) throw cancelled() }
                if (painter.recordAll(ordered, m.storage, candidates, m.active, scale, originX, originY, tileSize, r, checkCancelled)) {
                    ordered.finish()
                } else {
                    record(ordered, m, candidates, originX, originY, r, z, scale, isCancelled)
                }
            } catch (_: GlShapeScene.NeedsBatches) {
                record(GlShapeScene(px), m, candidates, originX, originY, r, z, scale, isCancelled)
            }
        if (scene.isEmpty) return Gpu.Empty
        if (isCancelled()) throw cancelled()
        val pixels = GlShapeRasterizer.render(scene) ?: return Gpu.Unavailable
        return Gpu.Drawn(pixels, px)
    }

    /** [drawInto]'s loop, recording into [scene]. */
    private fun record(
        scene: GlShapeScene,
        m: Model<T>,
        candidates: IntArray,
        originX: Double,
        originY: Double,
        r: Float,
        z: Int,
        scale: Double,
        isCancelled: () -> Boolean,
    ): GlShapeScene {
        val perZoom = perZoomOf(m, z)
        val lastDot = IntArray(if (painter.thinSubpixelOverlaps) tileSize * tileSize else 0)
        if (painter.thinSubpixelOverlaps) {
            for ((k, index) in candidates.withIndex()) {
                if (k % CANCEL_CHECK_EVERY == 0 && isCancelled()) throw cancelled()
                if (m.active != null && !m.active[index]) continue
                val item = m.storage[index] ?: continue
                if (!painter.isVisible(item)) continue
                val slot = painter.subpixelSlot(item, scale, originX, originY, tileSize)
                if (slot >= 0) lastDot[slot] = index + 1
            }
        }
        for ((k, index) in candidates.withIndex()) {
            if (k % CANCEL_CHECK_EVERY == 0 && isCancelled()) throw cancelled()
            if (m.active != null && !m.active[index]) continue
            val item = m.storage[index] ?: continue
            if (painter.thinSubpixelOverlaps) {
                val slot = painter.subpixelSlot(item, scale, originX, originY, tileSize)
                if (slot == -1 || (slot >= 0 && lastDot[slot] != index + 1)) continue
            }
            painter.record(scene, item, simplifiedAt(perZoom, index, item, scale), originX, originY, r)
        }
        return scene.finish()
    }

    private fun candidatesFor(
        m: Model<T>,
        originX: Double,
        originY: Double,
        scale: Double,
    ): IntArray {
        // A stroke reaches half its width past the shape: take the shapes that
        // close to the tile too, and draw them whole (the canvas cuts them), so
        // the tile next door draws the same line at the same place.
        val pad = m.maxStroke / 2.0 + 1.0
        return m.index.query(
            { index -> if (m.active?.get(index) == false) null else m.storage.bbox(index) },
            (originX - pad) / scale,
            (originY - pad) / scale,
            (originX + tileSize + pad) / scale,
            (originY + tileSize + pad) / scale,
        )
    }

    private fun draw(
        m: Model<T>,
        x: Int,
        y: Int,
        z: Int,
        pixelRatio: Int,
        isCancelled: () -> Boolean,
    ): Bitmap? {
        val scale = tileSize.toDouble() * (1L shl z).toDouble()
        val originX = x.toDouble() * tileSize
        val originY = y.toDouble() * tileSize
        val candidates = candidatesFor(m, originX, originY, scale)
        if (candidates.isEmpty()) return null

        val r = pixelScale() * pixelRatio
        val px = (tileSize * r).roundToInt().coerceAtLeast(1)
        val bitmap = scratch(px)
        try {
            return drawInto(bitmap, m, candidates, originX, originY, r, z, scale, isCancelled)
        } catch (error: Throwable) {
            releaseScratch(bitmap)
            throw error
        }
    }

    /** Draws into [bitmap]; null (and the bitmap back in the pool) when nothing was drawn. */
    @Suppress("UNCHECKED_CAST")
    private fun drawInto(
        bitmap: Bitmap,
        m: Model<T>,
        candidates: IntArray,
        originX: Double,
        originY: Double,
        r: Float,
        z: Int,
        scale: Double,
        isCancelled: () -> Boolean,
    ): Bitmap? {
        val canvas = Canvas(bitmap)
        val paints = Paints()
        val perZoom = perZoomOf(m, z)
        // Subpixel circles are display representatives: keep the last visible
        // circle at each world pixel. Never remove states or larger circles.
        val lastDot = IntArray(if (painter.thinSubpixelOverlaps) tileSize * tileSize else 0)
        if (painter.thinSubpixelOverlaps) {
            for ((k, index) in candidates.withIndex()) {
                if (k % CANCEL_CHECK_EVERY == 0 && isCancelled()) throw cancelled()
                if (m.active != null && !m.active[index]) continue
                val item = m.storage[index] ?: continue
                if (!painter.isVisible(item)) continue
                val slot = painter.subpixelSlot(item, scale, originX, originY, tileSize)
                if (slot >= 0) lastDot[slot] = index + 1
            }
        }
        var drew = false
        for ((k, index) in candidates.withIndex()) {
            if (k % CANCEL_CHECK_EVERY == 0 && isCancelled()) throw cancelled()
            if (m.active != null && !m.active[index]) continue
            val item = m.storage[index] ?: continue
            if (painter.thinSubpixelOverlaps) {
                val slot = painter.subpixelSlot(item, scale, originX, originY, tileSize)
                if (slot == -1 || (slot >= 0 && lastDot[slot] != index + 1)) continue
            }
            val shape = simplifiedAt(perZoom, index, item, scale)
            if (painter.draw(canvas, item, shape, originX, originY, r, paints)) drew = true
        }
        if (!drew) releaseScratch(bitmap)
        return if (drew) bitmap else null
    }

    private fun perZoomOf(
        m: Model<T>,
        z: Int,
    ): AtomicReferenceArray<Simplified?> =
        if (!painter.cacheSimplifiedShapes) {
            AtomicReferenceArray(0)
        } else {
            synchronized(m.perZoom) {
                // Access order: a lookup makes the zoom the most recent one.
                m.perZoom[z] ?: AtomicReferenceArray<Simplified?>(m.storage.size).also { created ->
                    m.perZoom[z] = created
                    val it = m.perZoom.keys.iterator()
                    while (m.perZoom.size > ZOOMS_KEPT && it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
            }
        }

    private fun simplifiedAt(
        perZoom: AtomicReferenceArray<Simplified?>,
        index: Int,
        item: T,
        scale: Double,
    ): Any {
        // Dot and Arc are cheap projections of compact circle data. Caching
        // them for every source circle at several zooms defeats raster tiling.
        if (!painter.cacheSimplifiedShapes) return painter.simplify(item, scale)
        val cached = perZoom.get(index)
        if (cached != null && cached.source === item) return cached.shape
        val shape = painter.simplify(item, scale)
        perZoom.set(index, Simplified(item, shape))
        return shape
    }

    private fun scratch(px: Int): Bitmap {
        val existing = scratchBitmaps.acquire { !it.isRecycled && it.width == px && it.height == px }
        if (existing != null) {
            existing.eraseColor(Color.TRANSPARENT)
            return existing
        }
        return createBitmap(px, px)
    }

    private fun releaseScratch(bitmap: Bitmap) {
        scratchBitmaps.release(bitmap)
    }

    /** The GPU's pixels as PNG: Rust when it can, else through a bitmap. */
    private fun encode(
        pixels: ByteBuffer,
        size: Int,
    ): ByteArray {
        TilePngEncoder.encode(pixels, size, size, premultiplied = true)?.let { return it }
        pixels.rewind()
        val bitmap = scratch(size)
        try {
            bitmap.copyPixelsFromBuffer(pixels)
            return encode(bitmap)
        } finally {
            releaseScratch(bitmap)
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        // Rust first, as for marker tiles: Bitmap.compress is several times slower.
        TilePngEncoder.encode(bitmap)?.let { return it }
        val out = byteStreams.acquire() ?: ByteArrayOutputStream(16 * 1024)
        try {
            out.reset()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            byteStreams.release(out)
        }
    }

    private fun dropTilesIn(
        boxes: List<DoubleArray>,
        strokePx: Float,
    ) {
        if (boxes.isEmpty()) return
        for (key in tileCache.snapshot().keys) {
            val z = (key ushr 56).toInt()
            val x = ((key ushr 27) and COORD_MASK).toInt()
            val y = (key and COORD_MASK).toInt()
            val scale = tileSize.toDouble() * (1L shl z).toDouble()
            val pad = (strokePx + 2.0) / scale
            val tx0 = x * tileSize / scale - pad
            val tx1 = (x + 1) * tileSize / scale + pad
            val ty0 = y * tileSize / scale - pad
            val ty1 = (y + 1) * tileSize / scale + pad
            if (boxes.any { b -> b[0] <= tx1 && b[2] >= tx0 && b[1] <= ty1 && b[3] >= ty0 }) {
                tileCache.remove(key)
            }
        }
    }

    private fun cacheKey(
        z: Int,
        x: Int,
        y: Int,
        ratio: Int,
    ): Long = (z.toLong() shl 56) or (ratio.toLong() shl 54) or (x.toLong() shl 27) or y.toLong()

    /** Thrown when the map stops wanting a tile mid-draw: a failure it may retry, never an empty tile. */
    @InternalMapConductorApi
    class TileRenderCancelled : Exception("shape tile no longer wanted")

    init {
        if (useGpu && painter.drawsOnGpu) prepareGpu
    }

    @InternalMapConductorApi
    companion object {
        const val DEFAULT_CACHE_BYTES = 16 * 1024 * 1024

        /**
         * Draw shape tiles on the GPU ([GlShapeRasterizer]) when it can. False
         * draws every tile on the canvas, to compare the two.
         */
        @Volatile
        var useGpu: Boolean = true

        /**
         * The GL contexts and shaders are made on first use. Start that when
         * shapes are about to be tiled, instead of making the first tiles the
         * map asks for wait for it.
         */
        private val prepareGpu: Unit by lazy {
            Thread({ GlShapeRasterizer.prepare() }, "mc-shape-gpu-prepare").apply { isDaemon = true }.start()
        }

        /** Simplified copies kept, most recent zooms first. */
        private const val ZOOMS_KEPT = 4
        private const val MAX_ZOOM = 26
        private const val CANCEL_CHECK_EVERY = 256
        private const val COORD_MASK = (1L shl 27) - 1
    }
}
