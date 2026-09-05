package com.mapconductor.core.marker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.tileserver.TilePngEncoder
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.tileserver.TileProviderInterface
import com.mapconductor.core.tileserver.TileRequest
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache

data class PointD(
    val x: Double,
    val y: Double,
)

/**
 * A tile renderer for markers that implements [TileProviderInterface].
 *
 * This renderer generates PNG tiles containing marker icons at the appropriate
 * positions and sizes for the requested zoom level. It is designed to be used
 * with a local tile server and RasterLayer for SDK-agnostic marker rendering.
 *
 * Features:
 * - Fixed pixel size markers (markers keep consistent screen size across zoom levels)
 * - Decluttering support (limits markers per tile at low zoom levels)
 * - Internal tile caching
 *
 * Thread Safety:
 * - [renderTile] may be called from multiple threads concurrently
 */
class MarkerTileRenderer<ActualMarker>(
    val markerManager: MarkerManager<ActualMarker>,
    val tileSize: Int,
    cacheSizeBytes: Int,
    private val debugTileOverlay: Boolean = false,
    private val iconScaleCallback: ((MarkerState, Int) -> Double)? = null,
    val extraIconScale: Double = 1.0,
    /** See [MarkerTilingOptions.declutterPx]. Zero draws every marker. */
    private val declutterPx: Int = 0,
) : TileProviderInterface {
    @Volatile
    private var cacheVersion: Int = 0

    private val tileCache: LruCache<Long, ByteArray> =
        object : LruCache<Long, ByteArray>(cacheSizeBytes) {
            override fun sizeOf(
                key: Long,
                value: ByteArray,
            ): Int = value.size
        }

    private val tileCacheLock = Any()

    private val tilesRendered = AtomicLong(0L)
    private val tilesCacheHits = AtomicLong(0L)
    private val stateEpoch = AtomicLong(0L)

    private fun bumpStateEpoch() {
        stateEpoch.incrementAndGet()
    }

    /**
     * Invalidates the internal tile cache.
     */
    fun invalidate() {
        cacheVersion = (cacheVersion + 1) and 0x7fffffff
        synchronized(tileCacheLock) { tileCache.evictAll() }
        bumpStateEpoch()
    }

    /**
     * Clears all cached tiles.
     */
    fun clear() {
        cacheVersion = (cacheVersion + 1) and 0x7fffffff
        synchronized(tileCacheLock) { tileCache.evictAll() }
        bumpStateEpoch()
    }

    private fun tileCacheKey(
        normalizedX: Int,
        y: Int,
        zoom: Int,
        debug: Boolean,
        cacheVersion: Int,
        tileSize: Int,
    ): Long {
        val version7 = (cacheVersion and 0x7f).toLong()
        val debug1 = if (debug) 1L else 0L
        val tileSize11 = (tileSize and 0x7ff).toLong()
        if (zoom in 0..24 && normalizedX in 0 until (1 shl 24) && y in 0 until (1 shl 24)) {
            val base =
                (y.toLong() and 0xFFFFFFL) or
                    ((normalizedX.toLong() and 0xFFFFFFL) shl 24) or
                    ((zoom.toLong() and 0x3fL) shl 48) or
                    (debug1 shl 54) or
                    (tileSize11 shl 55) or
                    (version7 shl 58)
            return base
        }
        var k = (normalizedX.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
        k = k xor (zoom.toLong() shl 16)
        k = k xor (debug1 shl 1)
        k = k xor (tileSize11 shl 2)
        k = k xor (version7 shl 13)
        k *= -0x3d4d51cb3a1b5a75L
        k = java.lang.Long.rotateLeft(k, 27)
        k *= -0x52dce729L
        return mixKey(k)
    }

    private fun mixKey(key: Long): Long {
        var k = key
        k = k xor (k ushr 33)
        k *= -0xae502812aa7333L
        k = k xor (k ushr 33)
        k *= -0x3b314601e57a13adL
        k = k xor (k ushr 33)
        return k
    }

    private val bitmapPoolLock = Any()
    private val bitmapPool = LinkedHashMap<Int, ArrayDeque<Bitmap>>()
    private var bitmapPoolCount = 0
    private val maxPoolBitmaps = 6
    private val maxPoolPerSize = 2

    private val scaledTileSize = ResourceProvider.dpToPx(tileSize.dp)
    private val debugPaint =
        Paint().apply {
            textSize = ResourceProvider.dpToPxForBitmap(10f).toFloat()
            color = Color.RED
            strokeWidth = ResourceProvider.dpToPxForBitmap(1f).toFloat()
            flags = Paint.ANTI_ALIAS_FLAG
        }

    private val bmpPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }

    private val defaultIcon = DefaultMarkerIcon()

    /**
     * Largest icon half-extent any tile has needed so far, in px.
     *
     * Seeds the padding used to widen a tile's marker query. Starts from the
     * default icon's own extent rather than a guess: the guess was 32dp, real
     * icons are larger, and every tile therefore paid a second query and a
     * second prepare.
     *
     * Volatile rather than synchronised: renderTile runs concurrently, and a
     * torn read costs at most one extra pass on one tile, which is what the
     * field exists to avoid in the first place.
     */
    @Volatile
    private var observedHalfExtentPx: Double = defaultIcon.toBitmapIcon().let { icon ->
        val width = icon.size.width.toDouble() * extraIconScale
        val height = icon.size.height.toDouble() * extraIconScale
        val anchorX = icon.anchor.x.toDouble()
        val anchorY = icon.anchor.y.toDouble()
        maxOf(
            max(kotlin.math.abs(width * anchorX), kotlin.math.abs(width * (1.0 - anchorX))),
            max(kotlin.math.abs(height * anchorY), kotlin.math.abs(height * (1.0 - anchorY))),
        )
    }

    override fun renderTile(request: TileRequest): ByteArray? {
        val zoomInt = request.z
        val worldTileCount = 1 shl zoomInt
        if (request.y !in 0 until worldTileCount) return null
        val normalizedX = normalizeTileX(request.x, worldTileCount)
        val tileYInt = request.y

        val cacheVersionSnapshot = cacheVersion
        val cacheKey =
            tileCacheKey(
                normalizedX = normalizedX,
                y = tileYInt,
                zoom = zoomInt,
                debug = debugTileOverlay,
                cacheVersion = cacheVersionSnapshot,
                tileSize = tileSize,
            )
        synchronized(tileCacheLock) {
            tileCache.get(cacheKey)?.let { cached ->
                tilesCacheHits.incrementAndGet()
                return cached
            }
        }

        val tileX = normalizedX.toDouble()
        val tileY = tileYInt.toDouble()
        val zoom = zoomInt.toDouble()
        val tilePx = scaledTileSize.coerceAtLeast(1.0)
        val tilePxInt = tilePx.toInt().coerceAtLeast(1)

        // Tile geographic bounds (NW and SE corners).
        val leftTop = tileToGeoPoint(tileX, tileY, zoom)
        val rightBottom = tileToGeoPoint(tileX + 1.0, tileY + 1.0, zoom)
        val bounds =
            GeoRectBounds().apply {
                extend(leftTop)
                extend(rightBottom)
            }

        data class PreparedMarker(
            val bitmap: Bitmap,
            val centerNorm: PointD,
            val drawW: Float,
            val drawH: Float,
            val anchor: Offset,
        )

        fun prepareMarkers(entities: List<MarkerEntityInterface<ActualMarker>>): Pair<List<PreparedMarker>, Double> {
            var maxHalfExtentPx = 0.0
            val prepared = ArrayList<PreparedMarker>(entities.size)
            for (entity in entities) {
                val stateIcon = entity.state.icon
                val icon = stateIcon?.toBitmapIcon() ?: defaultIcon.toBitmapIcon()
                val pos = entity.state.position
                val tilePoint = geoToTilePoint(pos.longitude, pos.latitude, zoomInt)
                val centerNorm =
                    PointD(
                        x = tilePoint.x - tileX,
                        y = tilePoint.y - tileY,
                    )
                val callbackScale =
                    (iconScaleCallback?.invoke(entity.state, zoomInt) ?: 1.0)
                        .coerceAtLeast(0.0)
                // icon.size already includes MarkerIconInterface.scale (baked into the
                // bitmap by toBitmapIcon), so it must not be applied again here.
                val scale = (callbackScale * extraIconScale).coerceAtLeast(0.0)
                val drawW = (icon.size.width.toDouble() * scale).coerceAtLeast(1.0)
                val drawH = (icon.size.height.toDouble() * scale).coerceAtLeast(1.0)
                val anchorX = icon.anchor.x.toDouble()
                val anchorY = icon.anchor.y.toDouble()
                val halfExtentX =
                    max(
                        kotlin.math.abs(drawW * anchorX),
                        kotlin.math.abs(drawW * (1.0 - anchorX)),
                    )
                val halfExtentY =
                    max(
                        kotlin.math.abs(drawH * anchorY),
                        kotlin.math.abs(drawH * (1.0 - anchorY)),
                    )
                maxHalfExtentPx = max(maxHalfExtentPx, max(halfExtentX, halfExtentY))
                prepared.add(
                    PreparedMarker(
                        bitmap = icon.bitmap,
                        centerNorm = centerNorm,
                        drawW = drawW.toFloat(),
                        drawH = drawH.toFloat(),
                        anchor = icon.anchor,
                    ),
                )
            }
            return prepared to maxHalfExtentPx
        }

        fun queryByHalfExtentPx(halfExtentPx: Double): List<MarkerEntityInterface<ActualMarker>> {
            val span = bounds.toSpan() ?: GeoPoint(0.0, 0.0)
            val padNorm = (halfExtentPx / tilePx).coerceAtLeast(0.0)
            val latPad = span.latitude * padNorm
            val lonPad = span.longitude * padNorm
            val extended = bounds.expandedByDegrees(latPad, lonPad)
            // タイルに描くのは「タイル担当」の entity だけ。
            //
            // 多くのプロバイダはコントローラの markerManager をそのままこのレンダラへ渡すため、
            // 絞らないとネイティブマーカーとして描いているもの（draggable / animation 付き、
            // および [MarkerViewportSwitch] が Native モードで出したもの）まで PNG に焼かれ、
            // 同じ場所へ二重に出る。回転させるとタイル側だけ傾くので、ゴーストとして見える。
            //
            // maptiler / longdo のようにタイル専用の manager を別に持つプロバイダでは
            // 全 entity が tiling = true なので、この絞り込みは何もしない。
            return markerManager.findMarkersInBounds(extended).filter { it.tiling }
        }

        // First query uses a conservative padding (in dp) so we capture markers slightly outside
        // the tile that can overlap its edges.
        // Start from the largest extent any tile has actually needed rather than
        // from a fixed 32dp guess. The guess was smaller than the real icons on
        // every tile measured, so the "conservative first pass" never paid off
        // and query+prepare simply ran twice for every tile. Widening it as
        // soon as one tile knows better costs one extra pass in total.
        val assumedHalfExtentPx = observedHalfExtentPx

        var entities = queryByHalfExtentPx(assumedHalfExtentPx)

        if (entities.isEmpty()) {
            if (!debugTileOverlay) return null
            val debugBitmap = createBitmap(tilePxInt, tilePxInt)
            Canvas(debugBitmap).also { c ->
                c.drawLine(0f, 0f, tilePxInt.toFloat(), 0f, debugPaint)
                c.drawLine(0f, 0f, 0f, tilePxInt.toFloat(), debugPaint)
                c.drawText("x/y/z=$tileX/$tileY/$zoom, entries=0", 20f, 20f, debugPaint)
            }
            val bytes = bitmapToByteArray(debugBitmap).also { if (!debugBitmap.isRecycled) debugBitmap.recycle() }
            tilesRendered.incrementAndGet()
            if (cacheVersionSnapshot == cacheVersion) {
                synchronized(tileCacheLock) { tileCache.put(cacheKey, bytes) }
            }
            return bytes
        }

        val result0 = prepareMarkers(entities)
        var prepared = result0.first
        var maxHalfExtentPx = result0.second
        if (maxHalfExtentPx > assumedHalfExtentPx + 1.0) {
            observedHalfExtentPx = maxHalfExtentPx
            entities = queryByHalfExtentPx(maxHalfExtentPx)
            val result2 = prepareMarkers(entities)
            prepared = result2.first
            maxHalfExtentPx = result2.second
        }

        if (prepared.isEmpty()) {
            if (!debugTileOverlay) return null
            val debugBitmap = createBitmap(tilePxInt, tilePxInt)
            Canvas(debugBitmap).also { c ->
                c.drawLine(0f, 0f, tilePxInt.toFloat(), 0f, debugPaint)
                c.drawLine(0f, 0f, 0f, tilePxInt.toFloat(), debugPaint)
                c.drawText("x/y/z=$tileX/$tileY/$zoom, entries=0", 20f, 20f, debugPaint)
            }
            val bytes = bitmapToByteArray(debugBitmap).also { if (!debugBitmap.isRecycled) debugBitmap.recycle() }
            tilesRendered.incrementAndGet()
            if (cacheVersionSnapshot == cacheVersion) {
                synchronized(tileCacheLock) { tileCache.put(cacheKey, bytes) }
            }
            return bytes
        }

        // Instead of allocating a fixed 3x tile, allocate (tile + padding*2) based on icon size.
        val paddingPx =
            kotlin.math
                .ceil(maxHalfExtentPx + 2.0)
                .toInt()
                .coerceAtLeast(2)
        val dstRect = Rect()
        val offscreenSize = tilePxInt + paddingPx * 2
        val offscreenBitmap = acquireBitmap(offscreenSize)
        offscreenBitmap.eraseColor(Color.TRANSPARENT)

        Canvas(offscreenBitmap).also { canvas ->
            if (debugTileOverlay) {
                val o = paddingPx.toFloat()
                canvas.drawLine(o, o, o + tilePxInt.toFloat(), o, debugPaint)
                canvas.drawLine(o, o, o, o + tilePxInt.toFloat(), debugPaint)
                canvas.drawText(
                    "x/y/z=$tileX/$tileY/$zoom, entries=${prepared.size}",
                    o + 20f,
                    o + 20f,
                    debugPaint,
                )
            }

            // Where each marker lands, and which icon it draws. Markers that
            // agree on all of it sit exactly on top of one another.
            val placements = LongArray(prepared.size)
            val icons = arrayOfNulls<Bitmap>(prepared.size)
            for ((index, m) in prepared.withIndex()) {
                val centerX = (m.centerNorm.x * tilePx) + paddingPx.toDouble()
                val centerY = (m.centerNorm.y * tilePx) + paddingPx.toDouble()
                // Whole pixels, deliberately. The destination comes out of a
                // projection, so it lands on a fraction of a pixel almost every
                // time, and a filtered blit to a non-integer rectangle costs
                // about twenty times an aligned one: 20k markers measured at
                // 4270 ms unaligned against 203 ms aligned on a Pixel 5a.
                // Rounding moves a pin by at most half a pixel, which is not
                // visible at icon scale, and when the icon is drawn at its
                // natural size this also turns the blit into a straight copy.
                val left = Math.round(centerX - m.drawW * m.anchor.x.toDouble()).toInt()
                val top = Math.round(centerY - m.drawH * m.anchor.y.toDouble()).toInt()
                val width = Math.round(m.drawW.toDouble()).toInt().coerceAtLeast(1)
                val height = Math.round(m.drawH.toDouble()).toInt().coerceAtLeast(1)
                // Packed rather than a data class: one Long per marker instead
                // of 20k short-lived objects for the GC to sweep up.
                placements[index] =
                    (left.toLong() and 0xFFFF shl 48) or
                        (top.toLong() and 0xFFFF shl 32) or
                        (width.toLong() and 0xFFFF shl 16) or
                        (height.toLong() and 0xFFFF)
                icons[index] = m.bitmap
            }

            // One survivor per group of markers that cover each other. What
            // counts as a group depends on the mode, but the pass is the same
            // one either way — a second pass over 144k markers costs more than
            // the drawing it saves.
            //
            //  - default: the exact same rectangle drawn with the exact same
            //    icon. Those are invisible whatever happens, so dropping them
            //    cannot change the tile. Markers sharing a rectangle but not an
            //    icon are all kept: a different icon may be transparent where
            //    the one above it is not.
            //
            //  - declutterPx > 0: everything landing in the same cell of that
            //    size. Markers a few pixels apart overlap almost completely,
            //    and thinning them is a judgement about the map rather than a
            //    free optimisation — hence opt-in.
            //
            // The last of each group wins, which is what painter's order would
            // have left visible.
            val declutter = declutterPx > 0
            val cell = declutterPx.toDouble()

            fun groupKey(packed: Long): Long {
                if (!declutter) return packed
                val left = (packed shr 48).toShort().toInt()
                val top = (packed shr 32).toShort().toInt()
                val cx = kotlin.math.floor(left / cell).toLong()
                val cy = kotlin.math.floor(top / cell).toLong()
                return (cx shl 32) xor (cy and 0xFFFFFFFFL)
            }

            // Grouped by sorting, not by hashing.
            //
            // A HashMap keyed by the group boxes a Long and an Int per marker,
            // and on a tile holding the whole dataset that is hundreds of
            // thousands of objects — enough that the tile after it failed to
            // allocate its bitmap and the process aborted inside
            // Canvas::create_canvas. Sorting an array of (group, index) pairs
            // needs two primitive arrays and answers the same question: the
            // winner of a group is the last index in its run.
            val order = LongArray(prepared.size)
            var pairs = 0
            for (index in prepared.indices) {
                if (icons[index] == null) continue
                // 24 bits of index under 40 of group, so sorting orders by
                // group first and by index within it.
                order[pairs++] = (groupKey(placements[index]) shl 24) or index.toLong()
            }
            java.util.Arrays.sort(order, 0, pairs)

            val draw = BooleanArray(prepared.size)
            var runStart = 0
            while (runStart < pairs) {
                val group = order[runStart] ushr 24
                var runEnd = runStart + 1
                while (runEnd < pairs && (order[runEnd] ushr 24) == group) runEnd++

                if (declutter) {
                    // One survivor per cell, whatever it draws.
                    draw[(order[runEnd - 1] and 0xFFFFFF).toInt()] = true
                } else {
                    // Same rectangle, but a different icon may be transparent
                    // where the one above it is not — so a group holding more
                    // than one icon keeps all of them.
                    val first = icons[(order[runStart] and 0xFFFFFF).toInt()]
                    var mixed = false
                    for (at in runStart + 1 until runEnd) {
                        if (icons[(order[at] and 0xFFFFFF).toInt()] !== first) {
                            mixed = true
                            break
                        }
                    }
                    if (mixed) {
                        for (at in runStart until runEnd) {
                            draw[(order[at] and 0xFFFFFF).toInt()] = true
                        }
                    } else {
                        draw[(order[runEnd - 1] and 0xFFFFFF).toInt()] = true
                    }
                }
                runStart = runEnd
            }

            for (index in prepared.indices) {
                if (!draw[index]) continue
                val icon = icons[index] ?: continue
                val packed = placements[index]
                // Sign-extended: a marker overhanging the tile's top or left
                // edge has a negative origin, and masking it back to 16 bits
                // unsigned would move it to the far side of the tile.
                val left = (packed shr 48).toShort().toInt()
                val top = (packed shr 32).toShort().toInt()
                val width = (packed shr 16 and 0xFFFF).toInt()
                val height = (packed and 0xFFFF).toInt()
                dstRect.set(left, top, left + width, top + height)
                canvas.drawBitmap(icon, null, dstRect, bmpPaint)
            }
        }

        val finalBitmap = acquireBitmap(tilePxInt)
        finalBitmap.eraseColor(Color.TRANSPARENT)
        Canvas(finalBitmap).also { canvas ->
            val src = Rect(paddingPx, paddingPx, paddingPx + tilePxInt, paddingPx + tilePxInt)
            val dst = Rect(0, 0, tilePxInt, tilePxInt)
            canvas.drawBitmap(offscreenBitmap, src, dst, bmpPaint)
        }

        val output = bitmapToByteArray(finalBitmap)
        releaseBitmap(offscreenBitmap)
        releaseBitmap(finalBitmap)
        tilesRendered.incrementAndGet()

        // Avoid caching if inputs changed mid-render (best-effort; renderTile can be concurrent).
        if (cacheVersionSnapshot == cacheVersion) {
            synchronized(tileCacheLock) { tileCache.put(cacheKey, output) }
        }
        return output
    }

    // renderTile() runs concurrently across LocalTileServer's worker threads. A fresh
    // ByteArrayOutputStream starts at 32 bytes and doubles its backing array on every
    // compress() call until it reaches the tile's steady-state size (often hundreds of KB
    // for dense tiles), so a new instance per call re-triggers that regrowth every time.
    // Keeping one per thread (reset, not recreated, before each use) lets the backing
    // array settle at its steady-state size and be reused across tiles.
    private val tileByteStream =
        ThreadLocal.withInitial { ByteArrayOutputStream(16 * 1024) }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        // Rust first: it is 4-6x faster than Bitmap.compress on the tiles this
        // renderer produces, and compress is what dominates a tile once the
        // drawing is aligned. Null means the native path was unavailable or
        // declined the bitmap, and the platform encoder takes over.
        TilePngEncoder.encode(bitmap)?.let { return it }

        // ThreadLocal.get() is a Java generic method, so Kotlin sees its return type as
        // nullable even though withInitial() guarantees a value; !! is safe here.
        val outputStream = tileByteStream.get()!!
        outputStream.reset()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        // toByteArray() still copies out a right-sized array: callers cache this array or
        // hand it to a socket write, both of which need a stable array that isn't mutated
        // by the next renderTile() call on this thread.
        return outputStream.toByteArray()
    }

    private fun acquireBitmap(size: Int): Bitmap {
        val safeSize = size.coerceAtLeast(1)
        synchronized(bitmapPoolLock) {
            val bucket = bitmapPool[safeSize]
            while (!bucket.isNullOrEmpty()) {
                val candidate = bucket.removeFirst()
                bitmapPoolCount = (bitmapPoolCount - 1).coerceAtLeast(0)
                if (!candidate.isRecycled && candidate.width == safeSize && candidate.height == safeSize) {
                    return candidate
                }
                if (!candidate.isRecycled) {
                    candidate.recycle()
                }
            }
        }
        return createBitmap(safeSize, safeSize)
    }

    private fun releaseBitmap(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val size = bitmap.width
        if (bitmap.height != size || size <= 0) {
            bitmap.recycle()
            return
        }
        synchronized(bitmapPoolLock) {
            if (bitmapPoolCount >= maxPoolBitmaps) {
                bitmap.recycle()
                return
            }
            val bucket = bitmapPool.getOrPut(size) { ArrayDeque() }
            if (bucket.size >= maxPoolPerSize) {
                bitmap.recycle()
                return
            }
            bucket.addLast(bitmap)
            bitmapPoolCount += 1
        }
    }

    private fun tileToGeoPoint(
        x: Double,
        y: Double,
        z: Double,
    ): GeoPoint {
        // Slippy map tile (XYZ) -> WGS84 (lat/lng).
        //
        // This returns the NW (top-left) corner of the tile.
        // If you need the center, use (x + 0.5, y + 0.5) instead.
        val n = 2.0.pow(z)
        val lonDeg = (x / n) * 360.0 - 180.0
        val latRad = atan(sinh(PI * (1.0 - 2.0 * (y / n))))
        val latDeg = latRad * 180.0 / PI
        return GeoPoint.fromLatLong(latitude = latDeg, longitude = lonDeg)
    }

    private fun geoToTilePoint(
        longitude: Double,
        latitude: Double,
        zoom: Int,
    ): PointD {
        // WGS84 (lat/lng) -> slippy map tile coordinates (XYZ), using Web Mercator.
        //
        // Returns the fractional tile coordinate (x,y) at the given zoom.
        val n = 2.0.pow(zoom.toDouble())

        // Wrap longitude into [-180, 180) then map to [0, n).
        val lonWrapped = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val x0 = ((lonWrapped + 180.0) / 360.0) * n
        val x = ((x0 % n) + n) % n

        // Clamp latitude to Web Mercator limits to avoid infinity.
        val latClamped = latitude.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        val latRad = latClamped * PI / 180.0
        val y =
            (1.0 - ln(tan(latRad) + (1.0 / cos(latRad))) / PI) / 2.0 * n

        // y can be slightly outside due to floating errors; clamp to valid range.
        val yClamped = y.coerceIn(0.0, n - 1e-9)

        return PointD(x = x, y = yClamped)
    }

    private fun normalizeTileX(
        x: Int,
        worldTileCount: Int,
    ): Int {
        val wrapped = x % worldTileCount
        return if (wrapped < 0) wrapped + worldTileCount else wrapped
    }

    private companion object {
        private const val MAX_MERCATOR_LAT = 85.05112878
    }
}
