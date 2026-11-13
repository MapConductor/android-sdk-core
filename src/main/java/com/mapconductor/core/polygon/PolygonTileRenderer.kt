package com.mapconductor.core.polygon

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.shapetile.GlShapeScene
import com.mapconductor.core.shapetile.ShapeTileRenderer
import com.mapconductor.core.shapetile.toTilePixels
import com.mapconductor.core.tileserver.TileProviderInterface
import com.mapconductor.core.tileserver.TileRequest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path

/**
 * Draws a set of polygons into raster tiles: the counterpart of the polygon
 * part of js-sdk-core's tile-sw.js "Shape tiles", served by
 * [com.mapconductor.core.tileserver.LocalTileServer].
 *
 * The tiles themselves (index, per-zoom simplification, cache, invalidation,
 * scratch bitmaps, encoding, cancellation) are [ShapeTileRenderer]'s, shared
 * with the polyline tiles; this is how a polygon is drawn in one: filled
 * nonzero, the outline only stroked (never the holes), a polygon under
 * [MIN_OUTLINED_PX] without its outline, and one under a pixel as the pixel.
 *
 * Geometry is in tile units (a tile is [tileSize] wide whatever its pixels);
 * a tile is drawn [pixelScale] pixels per unit, so a stroke of 1 dp is one
 * unit wide, as on the web where it is one CSS pixel.
 */
internal class PolygonTileRenderer(
    val tileSize: Int = 256,
    cacheSizeBytes: Int = DEFAULT_CACHE_BYTES,
    pixelScale: () -> Float = { ResourceProvider.getDensity() },
) : TileProviderInterface {
    /** The shared renderer this one draws with; what the tile server is given. */
    internal val shapes: ShapeTileRenderer<TilePolygon> =
        ShapeTileRenderer(tileSize, cacheSizeBytes, pixelScale, PolygonTilePainter) { TileRenderCancelled() }

    /** Replaces every polygon. Draw order is list order. */
    fun setPolygons(polygons: List<TilePolygon?>) = shapes.setItems(polygons)

    /**
     * Replaces the polygons at the given indexes. Only the tiles that
     * overlapped one of them, before or after, are drawn again.
     */
    fun update(updates: List<Pair<Int, TilePolygon?>>) = shapes.update(updates)

    fun clear() = shapes.clear()

    override fun renderTile(request: TileRequest): ByteArray? = shapes.renderTile(request)

    override fun renderTile(
        request: TileRequest,
        isCancelled: () -> Boolean,
    ): ByteArray? = shapes.renderTile(request, isCancelled)

    /** The tile as a bitmap, or null when nothing of any polygon is in it. For tests. */
    fun renderBitmap(
        x: Int,
        y: Int,
        z: Int,
        pixelRatio: Int = 1,
    ): Bitmap? = shapes.renderBitmap(x, y, z, pixelRatio)

    /** Thrown when the map stops wanting a tile mid-draw: a failure it may retry, never an empty tile. */
    class TileRenderCancelled : Exception("polygon tile no longer wanted")

    companion object {
        const val DEFAULT_CACHE_BYTES = ShapeTileRenderer.DEFAULT_CACHE_BYTES
    }
}

/** How a polygon is drawn in a shape tile. */
@InternalMapConductorApi
internal object PolygonTilePainter : ShapeTileRenderer.Painter<TilePolygon> {
    override fun bbox(item: TilePolygon): DoubleArray = item.bbox

    override fun strokeWidth(item: TilePolygon): Float = if (item.strokeVisible) item.strokeWidth else 0f

    override fun simplify(
        item: TilePolygon,
        scale: Double,
    ): Any = simplifyPolygon(item, scale)

    override fun draw(
        canvas: Canvas,
        item: TilePolygon,
        shape: Any,
        originX: Double,
        originY: Double,
        r: Float,
        paints: ShapeTileRenderer.Paints,
    ): Boolean {
        val fillVisible = item.fillVisible
        var drew = false
        when (shape) {
            is TileShape.Dot -> {
                if (!fillVisible && !item.strokeVisible) return false
                paints.dot.color = if (fillVisible) item.fill else item.stroke
                val left = ((shape.x - originX) * r).toFloat()
                val top = ((shape.y - originY) * r).toFloat()
                canvas.drawRect(left, top, left + r, top + r, paints.dot)
                drew = true
            }
            is TileShape.Rings -> {
                val path = paints.path
                if (fillVisible) {
                    path.rewind()
                    path.fillType = Path.FillType.WINDING
                    for (ring in shape.rings) addRing(path, ring, originX, originY, r)
                    paints.fill.color = item.fill
                    canvas.drawPath(path, paints.fill)
                    drew = true
                }
                if (item.strokeVisible && shape.outlined) {
                    // Outline only, as the map SDKs draw it.
                    path.rewind()
                    addRing(path, shape.rings[0], originX, originY, r)
                    paints.stroke.color = item.stroke
                    paints.stroke.strokeWidth = item.strokeWidth * r
                    canvas.drawPath(path, paints.stroke)
                    drew = true
                }
            }
        }
        return drew
    }

    override val drawsOnGpu: Boolean get() = ShapeTileRenderer.gpuPolygonsAndLines

    override fun record(
        scene: GlShapeScene,
        item: TilePolygon,
        shape: Any,
        originX: Double,
        originY: Double,
        r: Float,
    ): Boolean {
        val fillVisible = item.fillVisible
        when (shape) {
            is TileShape.Dot -> {
                if (!fillVisible && !item.strokeVisible) return false
                val left = ((shape.x - originX) * r).toFloat()
                val top = ((shape.y - originY) * r).toFloat()
                scene.addCircle(left, top, r, r, if (fillVisible) item.fill else item.stroke, 0, kind = 1)
                return true
            }
            is TileShape.Rings -> {
                val outlined = item.strokeVisible && shape.outlined
                if (!fillVisible && !outlined) return false
                scene.addPolygon(
                    shape.rings.map { toTilePixels(it, originX, originY, r) },
                    fill = if (fillVisible) item.fill else 0,
                    outline = if (outlined) item.stroke else 0,
                    strokeWidth = item.strokeWidth * r,
                )
                return true
            }
        }
        return false
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
}
