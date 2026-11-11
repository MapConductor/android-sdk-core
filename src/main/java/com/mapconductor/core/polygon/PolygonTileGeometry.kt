package com.mapconductor.core.polygon

import androidx.compose.ui.graphics.toArgb
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.shapetile.ShapeGridIndex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The geometry of polygon tiles, without any drawing: the same steps as the
// "Polygon tiles" section of js-sdk-core's tile-sw.js, so the three platforms
// draw the same pixels from the same polygons.
//
// Polygons become Web Mercator coordinates in [0, 1], with the outline wound
// one way and the holes the other, so a nonzero fill leaves out the union of
// the holes without computing it.
//
// Each zoom gets its own simplified copy of a polygon, built the first time a
// tile at that zoom needs it: vertices snapped to that zoom's pixel grid,
// repeats dropped, Douglas-Peucker at half a pixel, and a polygon smaller than
// a pixel drawn as one pixel. Taps never see any of this -- they are resolved
// against the original shapes in PolygonManager.

/** Cells of the polygon index, in Web Mercator units: one z12 tile. */
internal const val POLYGON_CELL = 1.0 / 4096.0

/** A polygon covering more cells than this is kept in a list every query checks. */
internal const val POLYGON_MAX_CELLS = 256

internal const val DP_TOLERANCE_PX = 0.5

/**
 * Below this size on screen a polygon's outline is left out: a 1 px line round
 * a 3 px parcel is the whole parcel, and a city of them zoomed out turns into
 * the outline colour.
 */
internal const val MIN_OUTLINED_PX = 4.0

private const val MAX_LATITUDE = 85.05112878

/**
 * One polygon as the tiles draw it.
 *
 * [rings] are Web Mercator `[x0, y0, x1, y1, ...]` in [0, 1], the outline
 * first and wound positive, every hole after it wound negative. [strokeWidth]
 * is in dp, the unit of [PolygonState.strokeWidth].
 */
internal class TilePolygon(
    val rings: Array<DoubleArray>,
    /** minX, minY, maxX, maxY of the outline. */
    val bbox: DoubleArray,
    val fill: Int,
    val stroke: Int,
    val strokeWidth: Float,
) {
    val fillVisible: Boolean get() = isVisibleColor(fill)
    val strokeVisible: Boolean get() = strokeWidth > 0f && isVisibleColor(stroke)

    fun sameBox(other: TilePolygon): Boolean = bbox.contentEquals(other.bbox)

    companion object {
        /** Null when the outline has fewer than three points. */
        fun from(state: PolygonState): TilePolygon? {
            val rings = ArrayList<DoubleArray>(1 + state.holes.size)
            val all = listOf(state.points) + state.holes
            for ((r, points) in all.withIndex()) {
                var ring = toMercatorRing(points)
                if (ring.size < 6) {
                    if (r == 0) return null
                    continue
                }
                // Outline positive, holes negative: opposite windings under nonzero.
                val area = signedArea(ring)
                if ((r == 0 && area < 0) || (r > 0 && area > 0)) ring = reversedRing(ring)
                rings.add(ring)
            }
            val outer = rings[0]
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var i = 0
            while (i < outer.size) {
                minX = min(minX, outer[i])
                maxX = max(maxX, outer[i])
                minY = min(minY, outer[i + 1])
                maxY = max(maxY, outer[i + 1])
                i += 2
            }
            return TilePolygon(
                rings = rings.toTypedArray(),
                bbox = doubleArrayOf(minX, minY, maxX, maxY),
                fill = state.fillColor.toArgb(),
                stroke = state.strokeColor.toArgb(),
                strokeWidth = state.strokeWidth.value,
            )
        }
    }
}

internal fun isVisibleColor(argb: Int): Boolean = (argb ushr 24) != 0

internal fun toMercatorRing(points: List<GeoPointInterface>): DoubleArray {
    val ring = DoubleArray(points.size * 2)
    for ((i, p) in points.withIndex()) {
        val lat = p.latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val s = sin(lat * PI / 180.0)
        ring[i * 2] = (p.longitude + 180.0) / 360.0
        ring[i * 2 + 1] = 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }
    return ring
}

internal fun signedArea(ring: DoubleArray): Double {
    var area = 0.0
    val n = ring.size / 2
    var j = n - 1
    for (i in 0 until n) {
        area += ring[j * 2] * ring[i * 2 + 1] - ring[i * 2] * ring[j * 2 + 1]
        j = i
    }
    return area / 2
}

internal fun reversedRing(ring: DoubleArray): DoubleArray {
    val n = ring.size / 2
    val out = DoubleArray(ring.size)
    for (i in 0 until n) {
        out[i * 2] = ring[(n - 1 - i) * 2]
        out[i * 2 + 1] = ring[(n - 1 - i) * 2 + 1]
    }
    return out
}

/**
 * The polygon index: polygons by the grid cells their bounding box covers.
 * The grid itself is [ShapeGridIndex], shared with the polyline tiles.
 */
internal class PolygonGridIndex(
    private val cells: HashMap<Long, IntArray>,
    private val large: IntArray,
) {
    private var grid = ShapeGridIndex(cells, large)

    /**
     * The polygons whose bounding box meets the given box, each once, in
     * ascending index order -- which is draw order.
     */
    fun query(
        polygons: Array<TilePolygon?>,
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
    ): IntArray = grid.query({ polygons[it]?.bbox }, minX, minY, maxX, maxY)

    companion object {
        fun build(polygons: Array<TilePolygon?>): PolygonGridIndex {
            val grid = ShapeGridIndex.build(polygons.size) { polygons[it]?.bbox }
            return PolygonGridIndex(grid.cells, grid.large).apply { this.grid = grid }
        }
    }
}

/** What one polygon looks like at one zoom. */
internal sealed class TileShape {
    /** World-pixel rings at the zoom, `[x0, y0, ...]`, outline first. */
    class Rings(
        val rings: Array<DoubleArray>,
        val outlined: Boolean,
    ) : TileShape()

    /** A polygon under a pixel, drawn as the pixel at [x], [y]. */
    class Dot(
        val x: Double,
        val y: Double,
    ) : TileShape()
}

/**
 * The polygon as drawn at a zoom whose world is [scale] pixels wide: rings of
 * world-pixel points, a dot for a polygon under a pixel, never nothing.
 */
internal fun simplifyPolygon(
    p: TilePolygon,
    scale: Double,
): TileShape {
    val w = (p.bbox[2] - p.bbox[0]) * scale
    val h = (p.bbox[3] - p.bbox[1]) * scale
    if (w < 1 && h < 1) {
        return TileShape.Dot(
            floor(((p.bbox[0] + p.bbox[2]) / 2) * scale),
            floor(((p.bbox[1] + p.bbox[3]) / 2) * scale),
        )
    }
    val outer =
        simplifyRing(p.rings[0], scale)
            ?: return TileShape.Dot(
                jsRound(((p.bbox[0] + p.bbox[2]) / 2) * scale),
                jsRound(((p.bbox[1] + p.bbox[3]) / 2) * scale),
            )
    val rings = ArrayList<DoubleArray>(p.rings.size)
    rings.add(outer)
    for (r in 1 until p.rings.size) {
        simplifyRing(p.rings[r], scale)?.let(rings::add)
    }
    return TileShape.Rings(rings.toTypedArray(), outlined = max(w, h) >= MIN_OUTLINED_PX)
}

/** Snapped to the pixel grid, repeats dropped, Douglas-Peucker; null under three points. */
internal fun simplifyRing(
    ring: DoubleArray,
    scale: Double,
): DoubleArray? {
    val snapped = DoubleArray(ring.size)
    var n = 0
    var px = Double.NaN
    var py = Double.NaN
    var i = 0
    while (i < ring.size) {
        val x = jsRound(ring[i] * scale)
        val y = jsRound(ring[i + 1] * scale)
        i += 2
        if (x == px && y == py) continue
        snapped[n++] = x
        snapped[n++] = y
        px = x
        py = y
    }
    val simplified = douglasPeucker(snapped, n / 2, DP_TOLERANCE_PX)
    return if (simplified.size >= 6) simplified else null
}

/** `Math.round` as JavaScript has it (halves go up), so pixels land where tile-sw.js puts them. */
private fun jsRound(v: Double): Double = floor(v + 0.5)

/** Douglas-Peucker on the first [n] points of [points]; returns the kept points. */
internal fun douglasPeucker(
    points: DoubleArray,
    n: Int,
    tolerance: Double,
): DoubleArray {
    if (n < 3) return points.copyOf(n * 2)
    val keep = BooleanArray(n)
    keep[0] = true
    keep[n - 1] = true
    val stack = IntList()
    stack.add(0)
    stack.add(n - 1)
    while (stack.size > 0) {
        val b = stack.pop()
        val a = stack.pop()
        val ax = points[a * 2]
        val ay = points[a * 2 + 1]
        val dx = points[b * 2] - ax
        val dy = points[b * 2 + 1] - ay
        val len = hypot(dx, dy)
        var best = -1.0
        var bestIndex = -1
        for (i in a + 1 until b) {
            val px = points[i * 2]
            val py = points[i * 2 + 1]
            val d = if (len != 0.0) abs(dy * (px - ax) - dx * (py - ay)) / len else hypot(px - ax, py - ay)
            if (d > best) {
                best = d
                bestIndex = i
            }
        }
        if (best > tolerance) {
            keep[bestIndex] = true
            stack.add(a)
            stack.add(bestIndex)
            stack.add(bestIndex)
            stack.add(b)
        }
    }
    var kept = 0
    for (k in keep) if (k) kept++
    val out = DoubleArray(kept * 2)
    var o = 0
    for (i in 0 until n) {
        if (keep[i]) {
            out[o++] = points[i * 2]
            out[o++] = points[i * 2 + 1]
        }
    }
    return out
}

/** Total vertices across outlines and holes. */
internal fun vertexCountOf(states: List<PolygonState>): Int {
    var n = 0
    for (s in states) {
        n += s.points.size
        for (h in s.holes) n += h.size
    }
    return n
}

/** A growable int array, so indexes do not box every polygon index. */
internal class IntList(
    capacity: Int = 8,
) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(max(8, size * 2))
        data[size++] = v
    }

    fun pop(): Int = data[--size]

    fun toArray(): IntArray = data.copyOf(size)

    fun toSortedDistinctArray(): IntArray {
        if (size == 0) return IntArray(0)
        val a = data.copyOf(size)
        a.sort()
        var n = 1
        for (i in 1 until a.size) if (a[i] != a[n - 1]) a[n++] = a[i]
        return if (n == a.size) a else a.copyOf(n)
    }
}
