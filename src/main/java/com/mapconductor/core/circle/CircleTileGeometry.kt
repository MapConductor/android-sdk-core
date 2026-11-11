package com.mapconductor.core.circle

import androidx.compose.ui.graphics.toArgb
import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.calculateZIndex
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.geometry.DEFAULT_CIRCLE_SEGMENTS
import com.mapconductor.core.geometry.circleToRing
import com.mapconductor.core.normalizeLng
import com.mapconductor.core.polygon.MIN_OUTLINED_PX
import com.mapconductor.core.polygon.isVisibleColor
import com.mapconductor.core.polygon.simplifyRing
import com.mapconductor.core.polygon.toMercatorRing
import com.mapconductor.core.projection.Earth
import com.mapconductor.core.shapetile.ShapeTileRenderer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import android.graphics.Canvas
import android.graphics.Path

// The geometry of circle tiles: the same steps as the circle part of the
// "Shape tiles" section of js-sdk-core's tile-sw.js and ios-sdk-core's
// CircleTileGeometry, so the three platforms draw the same pixels from the
// same circles.
//
// A circle's radius is a distance on the ground, so unlike a line's width it
// grows on screen with the zoom: twice as many pixels at z+1. Its stroke is a
// width on screen, as the map SDKs draw it, the same at every zoom.
//
// Its box comes from the same formulas as the native 128-gon (circleToRing):
// the spherical circle for geodesic, the local plane round the centre's
// latitude otherwise. At each zoom it is one of three shapes:
// - Dot: under a pixel across, one whole pixel, as a polygon under a pixel is.
// - Arc: a true circle, when Mercator's stretch (the north half taller than
//   the south) is a quarter of a pixel or less -- every city tree at every zoom.
// - Ring: otherwise (kilometres across, or far north) the native 128-gon in
//   world pixels, snapped and simplified as a polygon's outline is.
// Taps never see any of this -- they are resolved against the original circle
// in CircleManager.

private const val MAX_LATITUDE = 85.05112878

/** Under this radius on screen (in pixels) a circle is a single pixel. */
private const val CIRCLE_DOT_RADIUS_PX = 0.5

/** Mercator's stretch (in pixels) up to which a circle is drawn as a true circle. */
private const val CIRCLE_ARC_TOLERANCE_PX = 0.25

/** Web Mercator y in [0, 1] of [latitude], clamped as the shape tiles clamp it. */
private fun mercatorY(latitude: Double): Double {
    val lat = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
    val s = sin(lat * PI / 180.0)
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}

/**
 * The latitude and longitude extent of a circle, in degrees: what the native
 * 128-gon spans. [dLng] is the half-width east and west of the centre.
 */
@InternalMapConductorApi
internal class CircleExtent(
    val north: Double,
    val south: Double,
    val dLng: Double,
    /** Whether a geodesic circle reaches over a pole (then [dLng] means nothing). */
    val overPole: Boolean,
)

@InternalMapConductorApi
internal fun circleExtent(
    latitude: Double,
    radiusMeters: Double,
    geodesic: Boolean,
): CircleExtent {
    val phi = latitude * PI / 180.0
    if (geodesic) {
        // Spherical.computeOffset: the angular distance on a sphere of Earth.RADIUS_METERS.
        val delta = radiusMeters / Earth.RADIUS_METERS
        val deltaDeg = delta * 180.0 / PI
        val overPole = sin(delta) >= cos(phi)
        val dLng = if (overPole) 180.0 else asin(min(1.0, sin(delta) / cos(phi))) * 180.0 / PI
        return CircleExtent(latitude + deltaDeg, latitude - deltaDeg, dLng, overPole)
    }
    // circleToRing's local plane round the centre's latitude.
    val metersPerDegree = Earth.CIRCUMFERENCE_METERS / 360.0
    val dLat = radiusMeters / metersPerDegree
    val dLng = radiusMeters / (metersPerDegree * max(cos(phi), 1e-6))
    return CircleExtent(latitude + dLat, latitude - dLat, dLng, false)
}

/**
 * One circle as the tiles draw it.
 *
 * [x], [y] is the centre in Web Mercator [0, 1]; [halfWidth] the east-west
 * radius and [northY], [southY] the top and bottom, all in the same units.
 * [strokeWidth] is in dp, the unit of [CircleState.strokeWidth], which is also
 * the tile unit.
 */
@InternalMapConductorApi
internal class TileCircle(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val geodesic: Boolean,
    val x: Double,
    val y: Double,
    val halfWidth: Double,
    val northY: Double,
    val southY: Double,
    /** minX, minY, maxX, maxY. */
    val bbox: DoubleArray,
    val fill: Int,
    val stroke: Int,
    val strokeWidth: Float,
) {
    fun matches(state: CircleState): Boolean =
        latitude == state.center.latitude && longitude == normalizeLng(state.center.longitude) &&
            radiusMeters == state.radiusMeters && geodesic == state.geodesic &&
            fill == state.fillColor.toArgb() && stroke == state.strokeColor.toArgb() &&
            strokeWidth == state.strokeWidth.value

    // The spherical 128-gon is independent of zoom. Keep it once per immutable
    // circle rather than repeating trig and projection at every new zoom.
    val mercatorRing: DoubleArray
        get() = synchronized(ringCache) {
            ringCache[this] ?: toMercatorRing(circleToRing(GeoPoint.fromLatLong(latitude, longitude), radiusMeters, geodesic, DEFAULT_CIRCLE_SEGMENTS)).also {
                ringCache[this] = it
            }
        }

    val fillVisible: Boolean get() = isVisibleColor(fill)
    val strokeVisible: Boolean get() = strokeWidth > 0f && isVisibleColor(stroke)

    @InternalMapConductorApi
    companion object {
        // Nationwide circles must not retain a 128-vertex ring per station.
        private val ringCache = object : LinkedHashMap<TileCircle, DoubleArray>(4096, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileCircle, DoubleArray>?): Boolean = size > 4096
        }
        /** Null when the circle cannot be drawn in a tile (see [CircleTileSet.canTile]). */
        fun from(state: CircleState): TileCircle? {
            if (!CircleTileSet.canTile(state)) return null
            val lat = state.center.latitude
            val lng = normalizeLng(state.center.longitude)
            val extent = circleExtent(lat, state.radiusMeters, state.geodesic)
            val x = (lng + 180.0) / 360.0
            val y = mercatorY(lat)
            val halfWidth = extent.dLng / 360.0
            val northY = mercatorY(extent.north)
            val southY = mercatorY(extent.south)
            return TileCircle(
                latitude = lat,
                longitude = lng,
                radiusMeters = state.radiusMeters,
                geodesic = state.geodesic,
                x = x,
                y = y,
                halfWidth = halfWidth,
                northY = northY,
                southY = southY,
                bbox = doubleArrayOf(x - halfWidth, northY, x + halfWidth, southY),
                fill = state.fillColor.toArgb(),
                stroke = state.strokeColor.toArgb(),
                strokeWidth = state.strokeWidth.value,
            )
        }
    }
}

/** What one circle looks like at one zoom, in world pixels. */
@InternalMapConductorApi
internal sealed class CircleTileShape {
    /** Under a pixel across: the pixel at [x], [y] (its top-left corner). */
    @InternalMapConductorApi
    class Dot(
        val x: Double,
        val y: Double,
    ) : CircleTileShape()

    /** A true circle round [cx], [cy]. */
    @InternalMapConductorApi
    class Arc(
        val cx: Double,
        val cy: Double,
        val radius: Double,
        val outlined: Boolean,
    ) : CircleTileShape()

    /** The native 128-gon, snapped and simplified, `[x0, y0, ...]`. */
    @InternalMapConductorApi
    class Ring(
        val points: DoubleArray,
        val outlined: Boolean,
    ) : CircleTileShape()
}

/** The circle as drawn at a zoom whose world is [scale] pixels wide; never nothing. */
@InternalMapConductorApi
internal fun simplifyCircle(
    c: TileCircle,
    scale: Double,
): CircleTileShape {
    val cx = c.x * scale
    val cy = c.y * scale
    val rx = c.halfWidth * scale
    val ryN = (c.y - c.northY) * scale
    val ryS = (c.southY - c.y) * scale
    val maxR = max(rx, max(ryN, ryS))
    if (maxR < CIRCLE_DOT_RADIUS_PX) return CircleTileShape.Dot(floor(cx), floor(cy))
    val outlined = 2 * maxR >= MIN_OUTLINED_PX
    if (max(abs(ryN - rx), abs(ryS - rx)) <= CIRCLE_ARC_TOLERANCE_PX) {
        return CircleTileShape.Arc(cx, cy, rx, outlined)
    }
    val simplified = simplifyRing(c.mercatorRing, scale) ?: return CircleTileShape.Arc(cx, cy, rx, outlined)
    return CircleTileShape.Ring(simplified, outlined)
}

/** The order circles are drawn in, as the native renderers stack them: an unset zIndex is the automatic one. */
@InternalMapConductorApi
internal fun effectiveZIndex(state: CircleState): Int = state.zIndex ?: calculateZIndex(state.center)

/**
 * How a circle is drawn in a shape tile: filled, then outlined with the stroke
 * centred on the edge (half of it outside), as the map SDKs draw it. A circle
 * under [MIN_OUTLINED_PX] across has no outline; one under a pixel is a pixel.
 */
@InternalMapConductorApi
internal object CircleTilePainter : ShapeTileRenderer.Painter<TileCircle> {
    override val cacheSimplifiedShapes: Boolean get() = false
    override val thinSubpixelOverlaps: Boolean get() = true
    override fun isVisible(item: TileCircle): Boolean = item.fillVisible || item.strokeVisible
    override fun subpixelSlot(item: TileCircle, scale: Double, originX: Double, originY: Double, tileSize: Int): Int {
        val radius = max(item.halfWidth, max(item.y - item.northY, item.southY - item.y)) * scale
        if (radius >= CIRCLE_DOT_RADIUS_PX) return -2
        val x = floor(item.x * scale) - originX
        val y = floor(item.y * scale) - originY
        return if (x >= 0 && x < tileSize && y >= 0 && y < tileSize) y.toInt() * tileSize + x.toInt() else -1
    }

    override fun bbox(item: TileCircle): DoubleArray = item.bbox

    override fun strokeWidth(item: TileCircle): Float = if (item.strokeVisible) item.strokeWidth else 0f

    override fun simplify(
        item: TileCircle,
        scale: Double,
    ): Any = simplifyCircle(item, scale)

    override fun draw(
        canvas: Canvas,
        item: TileCircle,
        shape: Any,
        originX: Double,
        originY: Double,
        r: Float,
        paints: ShapeTileRenderer.Paints,
    ): Boolean {
        val fillVisible = item.fillVisible
        val strokeVisible = item.strokeVisible
        if (!fillVisible && !strokeVisible) return false
        when (shape) {
            is CircleTileShape.Dot -> {
                paints.dot.color = if (fillVisible) item.fill else item.stroke
                val left = ((shape.x - originX) * r).toFloat()
                val top = ((shape.y - originY) * r).toFloat()
                canvas.drawRect(left, top, left + r, top + r, paints.dot)
                return true
            }
            is CircleTileShape.Arc -> {
                val cx = ((shape.cx - originX) * r).toFloat()
                val cy = ((shape.cy - originY) * r).toFloat()
                val radius = (shape.radius * r).toFloat()
                var drew = false
                if (fillVisible) {
                    paints.fill.color = item.fill
                    canvas.drawCircle(cx, cy, radius, paints.fill)
                    drew = true
                }
                if (strokeVisible && shape.outlined) {
                    paints.stroke.color = item.stroke
                    paints.stroke.strokeWidth = item.strokeWidth * r
                    canvas.drawCircle(cx, cy, radius, paints.stroke)
                    drew = true
                }
                return drew
            }
            is CircleTileShape.Ring -> {
                val path = paints.path
                path.rewind()
                path.fillType = Path.FillType.WINDING
                val p = shape.points
                path.moveTo(((p[0] - originX) * r).toFloat(), ((p[1] - originY) * r).toFloat())
                var i = 2
                while (i < p.size) {
                    path.lineTo(((p[i] - originX) * r).toFloat(), ((p[i + 1] - originY) * r).toFloat())
                    i += 2
                }
                path.close()
                var drew = false
                if (fillVisible) {
                    paints.fill.color = item.fill
                    canvas.drawPath(path, paints.fill)
                    drew = true
                }
                if (strokeVisible && shape.outlined) {
                    paints.stroke.color = item.stroke
                    paints.stroke.strokeWidth = item.strokeWidth * r
                    canvas.drawPath(path, paints.stroke)
                    drew = true
                }
                return drew
            }
            else -> return false
        }
    }
}
