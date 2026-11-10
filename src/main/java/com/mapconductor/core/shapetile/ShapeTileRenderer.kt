package com.mapconductor.core.shapetile

import androidx.core.graphics.createBitmap
import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.tileserver.ScratchPool
import com.mapconductor.core.tileserver.TilePngEncoder
import com.mapconductor.core.tileserver.TileProviderInterface
import com.mapconductor.core.tileserver.TileRequest
import java.io.ByteArrayOutputStream
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
    private class Model(
        val items: Array<Any?>,
        val bboxes: Array<DoubleArray?>,
        val index: ShapeGridIndex,
        val maxStroke: Float,
        val perZoom: LinkedHashMap<Int, AtomicReferenceArray<Simplified?>>,
    )

    @Volatile
    private var model: Model? = null

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
        val array: Array<Any?> = items.toTypedArray<Any?>()
        val bboxes = Array(array.size) { i -> bboxAt(array, i) }
        val index = ShapeGridIndex.build(bboxes.size) { bboxes[it] }
        model = Model(array, bboxes, index, maxStrokeOf(array), LinkedHashMap(8, 0.75f, true))
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
        val current = model ?: return
        val items = current.items.copyOf()
        val bboxes = current.bboxes.copyOf()
        val touched = ArrayList<DoubleArray>(updates.size * 2)
        var maxStroke = current.maxStroke
        var reindex = false
        for ((index, after) in updates) {
            if (index !in items.indices) continue
            val before = bboxes[index]
            items[index] = after
            val afterBox = after?.let(painter::bbox)
            bboxes[index] = afterBox
            before?.let(touched::add)
            afterBox?.let(touched::add)
            if (after != null) maxStroke = max(maxStroke, painter.strokeWidth(after))
            // A shape that moved needs the index rebuilt; a restyle does not.
            if (before == null || afterBox == null || !before.contentEquals(afterBox)) reindex = true
        }
        val index = if (reindex) ShapeGridIndex.build(bboxes.size) { bboxes[it] } else current.index
        model = Model(items, bboxes, index, maxStroke, current.perZoom)
        synchronized(cacheLock) {
            generation.incrementAndGet()
            dropTilesIn(touched, maxStroke)
        }
    }

    fun clear() {
        model = null
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
        val m = model ?: return null
        val z = request.z
        if (z !in 0..MAX_ZOOM) return null
        val n = 1 shl z
        val x = ((request.x % n) + n) % n
        val y = request.y
        if (y !in 0 until n) return null
        val ratio = request.pixelRatio.coerceIn(1, 3)
        val key = cacheKey(z, x, y, ratio)
        synchronized(cacheLock) { tileCache.get(key) }?.let { return it }

        val bitmap = draw(m, x, y, z, ratio, isCancelled) ?: return null
        val bytes =
            try {
                encode(bitmap)
            } finally {
                releaseScratch(bitmap)
            }
        synchronized(cacheLock) {
            // The shapes may have changed while this tile was drawn.
            if (generation.get() == generationAtStart) tileCache.put(key, bytes)
        }
        return bytes
    }

    /** The tile as a bitmap, or null when nothing of any shape is in it. For tests and [renderTile]. */
    fun renderBitmap(
        x: Int,
        y: Int,
        z: Int,
        pixelRatio: Int = 1,
    ): Bitmap? =
        model?.let { draw(it, x, y, z, pixelRatio) { false } }?.let { scratch ->
            try {
                scratch.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                releaseScratch(scratch)
            }
        }

    private fun draw(
        m: Model,
        x: Int,
        y: Int,
        z: Int,
        pixelRatio: Int,
        isCancelled: () -> Boolean,
    ): Bitmap? {
        val scale = tileSize.toDouble() * (1L shl z).toDouble()
        val originX = x.toDouble() * tileSize
        val originY = y.toDouble() * tileSize
        // A stroke reaches half its width past the shape: take the shapes that
        // close to the tile too, and draw them whole (the canvas cuts them), so
        // the tile next door draws the same line at the same place.
        val pad = m.maxStroke / 2.0 + 1.0
        val bboxes = m.bboxes
        val candidates =
            m.index.query(
                { bboxes[it] },
                (originX - pad) / scale,
                (originY - pad) / scale,
                (originX + tileSize + pad) / scale,
                (originY + tileSize + pad) / scale,
            )
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
        m: Model,
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
        var drew = false
        for ((k, index) in candidates.withIndex()) {
            if (k % CANCEL_CHECK_EVERY == 0 && isCancelled()) throw cancelled()
            val item = (m.items[index] ?: continue) as T
            val shape = simplifiedAt(perZoom, index, item, scale)
            if (painter.draw(canvas, item, shape, originX, originY, r, paints)) drew = true
        }
        if (!drew) releaseScratch(bitmap)
        return if (drew) bitmap else null
    }

    private fun perZoomOf(
        m: Model,
        z: Int,
    ): AtomicReferenceArray<Simplified?> =
        synchronized(m.perZoom) {
            // Access order: a lookup makes the zoom the most recent one.
            m.perZoom[z] ?: AtomicReferenceArray<Simplified?>(m.items.size).also { created ->
                m.perZoom[z] = created
                val it = m.perZoom.keys.iterator()
                while (m.perZoom.size > ZOOMS_KEPT && it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }

    private fun simplifiedAt(
        perZoom: AtomicReferenceArray<Simplified?>,
        index: Int,
        item: T,
        scale: Double,
    ): Any {
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

    @Suppress("UNCHECKED_CAST")
    private fun bboxAt(
        items: Array<Any?>,
        i: Int,
    ): DoubleArray? = (items[i] as T?)?.let(painter::bbox)

    @Suppress("UNCHECKED_CAST")
    private fun maxStrokeOf(items: Array<Any?>): Float {
        var maxStroke = 0f
        for (item in items) if (item != null) maxStroke = max(maxStroke, painter.strokeWidth(item as T))
        return maxStroke
    }

    /** Thrown when the map stops wanting a tile mid-draw: a failure it may retry, never an empty tile. */
    @InternalMapConductorApi
    class TileRenderCancelled : Exception("shape tile no longer wanted")

    @InternalMapConductorApi
    companion object {
        const val DEFAULT_CACHE_BYTES = 16 * 1024 * 1024

        /** Simplified copies kept, most recent zooms first. */
        private const val ZOOMS_KEPT = 4
        private const val MAX_ZOOM = 26
        private const val CANCEL_CHECK_EVERY = 256
        private const val COORD_MASK = (1L shl 27) - 1
    }
}
