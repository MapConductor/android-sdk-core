package com.mapconductor.core.polyline

import androidx.compose.ui.graphics.toArgb
import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.polygon.DP_TOLERANCE_PX
import com.mapconductor.core.polygon.douglasPeucker
import com.mapconductor.core.polygon.isVisibleColor
import com.mapconductor.core.polygon.toMercatorRing
import com.mapconductor.core.shapetile.GlShapeScene
import com.mapconductor.core.shapetile.ShapeTileRenderer
import com.mapconductor.core.shapetile.toTilePixels
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import android.graphics.Canvas

// The geometry of polyline tiles: the same steps as the polyline part of the
// "Shape tiles" section of js-sdk-core's tile-sw.js and ios-sdk-core's
// PolylineTileGeometry, so the three platforms draw the same pixels from the
// same lines.
//
// A line becomes a Web Mercator path in [0, 1]. Each zoom gets its own
// simplified copy, built the first time a tile at that zoom needs it: points
// snapped to that zoom's pixel grid, repeats dropped, Douglas-Peucker at half
// a pixel with both ends kept. A line that collapses to one point (or whose
// box is under a pixel) is a dot as wide as the line. Taps never see any of
// this -- they are resolved against the original points in PolylineManager.

/**
 * One polyline as the tiles draw it.
 *
 * [path] is Web Mercator `[x0, y0, x1, y1, ...]` in [0, 1], open (never
 * closed by the tiles). [strokeWidth] is in dp, the unit of
 * [PolylineState.strokeWidth], which is also the tile unit: the line is as
 * wide on screen at every zoom.
 */
@InternalMapConductorApi
internal class TilePolyline(
    val path: DoubleArray,
    /** minX, minY, maxX, maxY. */
    val bbox: DoubleArray,
    val color: Int,
    val strokeWidth: Float,
) {
    val visible: Boolean get() = strokeWidth > 0f && isVisibleColor(color)

    @InternalMapConductorApi
    companion object {
        /** Null when the line has fewer than two points. */
        fun from(state: PolylineState): TilePolyline? {
            if (state.points.size < 2) return null
            val path = toMercatorRing(state.points)
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var i = 0
            while (i < path.size) {
                minX = min(minX, path[i])
                maxX = max(maxX, path[i])
                minY = min(minY, path[i + 1])
                maxY = max(maxY, path[i + 1])
                i += 2
            }
            return TilePolyline(
                path = path,
                bbox = doubleArrayOf(minX, minY, maxX, maxY),
                color = state.strokeColor.toArgb(),
                strokeWidth = state.strokeWidth.value,
            )
        }
    }
}

/** What one polyline looks like at one zoom. */
@InternalMapConductorApi
internal sealed class PolylineTileShape {
    /** World-pixel points at the zoom, `[x0, y0, ...]`, two or more. */
    @InternalMapConductorApi
    class Path(
        val points: DoubleArray,
    ) : PolylineTileShape()

    /** A line that is one point at this zoom, drawn as a dot as wide as the line, centred at [x], [y]. */
    @InternalMapConductorApi
    class Dot(
        val x: Double,
        val y: Double,
    ) : PolylineTileShape()
}

/**
 * The line as drawn at a zoom whose world is [scale] pixels wide: a path of
 * world-pixel points, or a dot for a line that is one point there; never
 * nothing.
 */
@InternalMapConductorApi
internal fun simplifyPolyline(
    line: TilePolyline,
    scale: Double,
): PolylineTileShape {
    val w = (line.bbox[2] - line.bbox[0]) * scale
    val h = (line.bbox[3] - line.bbox[1]) * scale
    if (w < 1 && h < 1) {
        return PolylineTileShape.Dot(
            ((line.bbox[0] + line.bbox[2]) / 2) * scale,
            ((line.bbox[1] + line.bbox[3]) / 2) * scale,
        )
    }
    val path = line.path
    val snapped = DoubleArray(path.size)
    var n = 0
    var px = Double.NaN
    var py = Double.NaN
    var i = 0
    while (i < path.size) {
        val x = jsRound(path[i] * scale)
        val y = jsRound(path[i + 1] * scale)
        i += 2
        if (x == px && y == py) continue
        snapped[n++] = x
        snapped[n++] = y
        px = x
        py = y
    }
    if (n < 4) return PolylineTileShape.Dot(snapped[0], snapped[1])
    // Douglas-Peucker keeps both ends, so the line still starts and ends where it did.
    return PolylineTileShape.Path(douglasPeucker(snapped, n / 2, DP_TOLERANCE_PX))
}

/** `Math.round` as JavaScript has it (halves go up), so pixels land where tile-sw.js puts them. */
private fun jsRound(v: Double): Double = floor(v + 0.5)

/** Total points across [states]. */
@InternalMapConductorApi
internal fun polylineVertexCountOf(states: List<PolylineState>): Int {
    var n = 0
    for (s in states) n += s.points.size
    return n
}

/**
 * How a polyline is drawn in a shape tile: stroked, never filled, with round
 * caps and joins (as the map SDKs' lines and the polygon tiles' outlines are),
 * the stroke as wide in dp as [PolylineState.strokeWidth] at every zoom.
 */
@InternalMapConductorApi
internal object PolylineTilePainter : ShapeTileRenderer.Painter<TilePolyline> {
    override fun bbox(item: TilePolyline): DoubleArray = item.bbox

    override fun strokeWidth(item: TilePolyline): Float = if (item.visible) item.strokeWidth else 0f

    override fun simplify(
        item: TilePolyline,
        scale: Double,
    ): Any = simplifyPolyline(item, scale)

    override fun draw(
        canvas: Canvas,
        item: TilePolyline,
        shape: Any,
        originX: Double,
        originY: Double,
        r: Float,
        paints: ShapeTileRenderer.Paints,
    ): Boolean {
        if (!item.visible) return false
        when (shape) {
            is PolylineTileShape.Dot -> {
                paints.fill.color = item.color
                val radius = max(item.strokeWidth * r, 1f) / 2f
                val cx = ((shape.x - originX) * r).toFloat()
                val cy = ((shape.y - originY) * r).toFloat()
                canvas.drawCircle(cx, cy, radius, paints.fill)
            }
            is PolylineTileShape.Path -> {
                val path = paints.path
                path.rewind()
                val p = shape.points
                path.moveTo(((p[0] - originX) * r).toFloat(), ((p[1] - originY) * r).toFloat())
                var i = 2
                while (i < p.size) {
                    path.lineTo(((p[i] - originX) * r).toFloat(), ((p[i + 1] - originY) * r).toFloat())
                    i += 2
                }
                paints.stroke.color = item.color
                paints.stroke.strokeWidth = item.strokeWidth * r
                canvas.drawPath(path, paints.stroke)
            }
            else -> return false
        }
        return true
    }

    override val drawsOnGpu: Boolean get() = ShapeTileRenderer.gpuPolygonsAndLines

    override fun record(
        scene: GlShapeScene,
        item: TilePolyline,
        shape: Any,
        originX: Double,
        originY: Double,
        r: Float,
    ): Boolean {
        if (!item.visible) return false
        when (shape) {
            is PolylineTileShape.Dot -> {
                val radius = max(item.strokeWidth * r, 1f) / 2f
                scene.addLineDot(((shape.x - originX) * r).toFloat(), ((shape.y - originY) * r).toFloat(), radius, item.color)
            }
            is PolylineTileShape.Path -> scene.addLine(toTilePixels(shape.points, originX, originY, r), item.color, item.strokeWidth * r)
            else -> return false
        }
        return true
    }
}
