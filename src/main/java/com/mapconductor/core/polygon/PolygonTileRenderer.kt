package com.mapconductor.core.polygon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import androidx.core.graphics.createBitmap
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

/**
 * Draws a set of polygons into raster tiles: the counterpart of the "Polygon
 * tiles" section of js-sdk-core's tile-sw.js, served by [com.mapconductor.core.tileserver.LocalTileServer].
 *
 * Geometry is in tile units (a tile is [tileSize] wide whatever its pixels);
 * a tile is drawn [pixelScale] pixels per unit, so a stroke of 1 dp is one
 * unit wide, as on the web where it is one CSS pixel.
 *
 * Tiles are cached here without the version the map sees in their URL. An
 * in-place change ([update]) drops only the tiles that overlap what changed;
 * the map asks again for every tile because the version moved, and the ones
 * the change did not touch come straight back from the cache.
 *
 * [renderTile] runs concurrently on the tile server's threads. Every change
 * replaces the model it reads, so a tile is drawn from one consistent set of
 * polygons.
 */
internal class PolygonTileRenderer(
    val tileSize: Int = 256,
    cacheSizeBytes: Int = DEFAULT_CACHE_BYTES,
    private val pixelScale: () -> Float = { ResourceProvider.getDensity() },
) : TileProviderInterface {
    private class Simplified(
        val source: TilePolygon,
        val shape: TileShape,
    )

    /**
     * What tiles are drawn from. [perZoom] is shared by the models an
     * [update] derives from one [setPolygons]: an entry is used only while its
     * source is still the polygon at that index, so a changed polygon is
     * simplified again without clearing anything.
     */
    private class Model(
        val polygons: Array<TilePolygon?>,
        val index: PolygonGridIndex,
        val maxStrokePx: Float,
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

    /** Replaces every polygon. Draw order is list order. */
    fun setPolygons(polygons: List<TilePolygon?>) {
        val array = polygons.toTypedArray()
        val next = Model(array, PolygonGridIndex.build(array), maxStrokeOf(array), LinkedHashMap(8, 0.75f, true))
        model = next
        synchronized(cacheLock) {
            generation.incrementAndGet()
            tileCache.evictAll()
        }
    }

    /**
     * Replaces the polygons at the given indexes. Only the tiles that
     * overlapped one of them, before or after, are drawn again.
     */
    fun update(updates: List<Pair<Int, TilePolygon?>>) {
        val current = model ?: return
        val polygons = current.polygons.copyOf()
        val touched = ArrayList<DoubleArray>(updates.size * 2)
        var maxStroke = current.maxStrokePx
        var reindex = false
        for ((index, after) in updates) {
            if (index !in polygons.indices) continue
            val before = polygons[index]
            polygons[index] = after
            before?.let { touched.add(it.bbox) }
            after?.let { touched.add(it.bbox) }
            if (after != null && after.strokeVisible) maxStroke = max(maxStroke, after.strokeWidth)
            // A shape that moved needs the index rebuilt; a restyle does not.
            if (before == null || after == null || !before.sameBox(after)) reindex = true
        }
        val index = if (reindex) PolygonGridIndex.build(polygons) else current.index
        model = Model(polygons, index, maxStroke, current.perZoom)
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
            // The polygons may have changed while this tile was drawn.
            if (generation.get() == generationAtStart) tileCache.put(key, bytes)
        }
        return bytes
    }

    /** The tile as a bitmap, or null when nothing of any polygon is in it. For tests and [renderTile]. */
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
        val pad = m.maxStrokePx / 2.0 + 1.0
        val candidates =
            m.index.query(
                m.polygons,
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
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
        val dotPaint = Paint().apply { style = Paint.Style.FILL }
        val path = Path().apply { fillType = Path.FillType.WINDING }
        val perZoom = perZoomOf(m, z)
        var drew = false
        for ((k, index) in candidates.withIndex()) {
            if (k % CANCEL_CHECK_EVERY == 0 && isCancelled()) throw TileRenderCancelled()
            val p = m.polygons[index] ?: continue
            val shape = simplifiedAt(perZoom, index, p, scale)
            val fillVisible = p.fillVisible
            when (shape) {
                is TileShape.Dot -> {
                    if (!fillVisible && !p.strokeVisible) continue
                    dotPaint.color = if (fillVisible) p.fill else p.stroke
                    val left = ((shape.x - originX) * r).toFloat()
                    val top = ((shape.y - originY) * r).toFloat()
                    canvas.drawRect(left, top, left + r, top + r, dotPaint)
                    drew = true
                }
                is TileShape.Rings -> {
                    if (fillVisible) {
                        path.rewind()
                        path.fillType = Path.FillType.WINDING
                        for (ring in shape.rings) addRing(path, ring, originX, originY, r)
                        fillPaint.color = p.fill
                        canvas.drawPath(path, fillPaint)
                        drew = true
                    }
                    if (p.strokeVisible && shape.outlined) {
                        // Outline only, as the map SDKs draw it.
                        path.rewind()
                        addRing(path, shape.rings[0], originX, originY, r)
                        strokePaint.color = p.stroke
                        strokePaint.strokeWidth = p.strokeWidth * r
                        canvas.drawPath(path, strokePaint)
                        drew = true
                    }
                }
            }
        }
        if (!drew) releaseScratch(bitmap)
        return if (drew) bitmap else null
    }

    private fun addRing(
        path: Path,
        ring: DoubleArray,
        originX: Double,
        originY: Double,
        r: Float,
    ) {
        path.moveTo(((ring[0] - originX) * r).toFloat(), ((ring[1] - originY) * r).toFloat())
        var i = 2
        while (i < ring.size) {
            path.lineTo(((ring[i] - originX) * r).toFloat(), ((ring[i + 1] - originY) * r).toFloat())
            i += 2
        }
        path.close()
    }

    private fun perZoomOf(
        m: Model,
        z: Int,
    ): AtomicReferenceArray<Simplified?> =
        synchronized(m.perZoom) {
            // Access order: a lookup makes the zoom the most recent one.
            m.perZoom[z] ?: AtomicReferenceArray<Simplified?>(m.polygons.size).also { created ->
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
        p: TilePolygon,
        scale: Double,
    ): TileShape {
        val cached = perZoom.get(index)
        if (cached != null && cached.source === p) return cached.shape
        val shape = simplifyPolygon(p, scale)
        perZoom.set(index, Simplified(p, shape))
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

    /** Thrown when the map stops wanting a tile mid-draw: a failure it may retry, never an empty tile. */
    class TileRenderCancelled : Exception("polygon tile no longer wanted")

    companion object {
        const val DEFAULT_CACHE_BYTES = 16 * 1024 * 1024

        /** Simplified copies kept, most recent zooms first. */
        private const val ZOOMS_KEPT = 4
        private const val MAX_ZOOM = 26
        private const val CANCEL_CHECK_EVERY = 256
        private const val COORD_MASK = (1L shl 27) - 1

        private fun maxStrokeOf(polygons: Array<TilePolygon?>): Float {
            var maxStroke = 0f
            for (p in polygons) if (p != null && p.strokeVisible) maxStroke = max(maxStroke, p.strokeWidth)
            return maxStroke
        }
    }
}
