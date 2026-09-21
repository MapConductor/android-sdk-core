package com.mapconductor.core.marker

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Neighbouring tiles must agree about the markers they share.
 *
 * Tiles are drawn one at a time. A marker drawn on tile A but not on tile B
 * gets **cut** at the boundary: the left half appears on the left tile and the
 * rest is nowhere.
 *
 * The renderer queries its own box grown by the icon overhang
 * (`queryByHalfExtentPx`), so the property that keeps the seam intact is:
 * a marker returned for A that also falls inside B's grown box must be
 * returned for B. That is all this test looks at — it never draws anything.
 *
 * ios-sdk's `MarkerTileSeamTests` and react-sdk's `MarkerTileSeam.test.mjs`
 * check the same thing.
 */
class MarkerTileSeamTest {
    private val tileSize = 512.0

    /** The sample's declutter. The disagreement shows up as a side effect of thinning. */
    private val declutterPx = 14.0

    /** Half an icon. The renderer measures it; a fixed value is enough here. */
    private val halfExtentPx = 10.0

    private fun markers(count: Int): List<MarkerEntityInterface<Int>> {
        var seed = 12345uL
        fun next(): Double {
            seed = seed * 6364136223846793005uL + 1442695040888963407uL
            return (seed shr 11).toDouble() / (1uL shl 53).toDouble()
        }
        return (0 until count).map { at ->
            MarkerEntity(
                marker = at,
                state =
                    MarkerState(
                        position = GeoPoint.fromLatLong(35.5 + next() * 0.4, 139.5 + next() * 0.5),
                        id = "$at",
                    ),
            )
        }
    }

    /** Tile coordinates to a lat/lng box. Same formulas as the renderer. */
    private fun tileBounds(
        x: Int,
        y: Int,
        z: Int,
    ): GeoRectBounds {
        val n = Math.pow(2.0, z.toDouble())
        fun lon(tx: Double) = tx / n * 360.0 - 180.0
        fun lat(ty: Double) = Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (ty / n)))) * 180.0 / Math.PI
        return GeoRectBounds(
            southWest = GeoPoint.fromLatLong(lat((y + 1).toDouble()), lon(x.toDouble())),
            northEast = GeoPoint.fromLatLong(lat(y.toDouble()), lon((x + 1).toDouble())),
        )
    }

    private data class Asked(
        val expanded: GeoRectBounds,
        val kept: List<MarkerEntityInterface<Int>>,
        val thinned: Boolean,
    )

    /**
     * The box and separation the renderer actually asks with.
     *
     * At zooms where the separation is finer than the bottom level the index
     * declines and the renderer falls back to the full query. That path has to
     * hold the seam too, so when it declines this checks `inBounds` instead.
     */
    private fun ask(
        index: MarkerGridIndex<Int>,
        x: Int,
        y: Int,
        z: Int,
    ): Asked {
        val bounds = tileBounds(x, y, z)
        val southWest = bounds.southWest!!
        val northEast = bounds.northEast!!
        val latSpan = northEast.latitude - southWest.latitude
        val lonSpan = northEast.longitude - southWest.longitude
        val padNorm = halfExtentPx / tileSize
        val expanded =
            GeoRectBounds(
                southWest =
                    GeoPoint.fromLatLong(
                        southWest.latitude - latSpan * padNorm,
                        southWest.longitude - lonSpan * padNorm,
                    ),
                northEast =
                    GeoPoint.fromLatLong(
                        northEast.latitude + latSpan * padNorm,
                        northEast.longitude + lonSpan * padNorm,
                    ),
            )
        val separation = maxOf(latSpan, lonSpan) * declutterPx / tileSize
        val thinned = index.inBoundsThinned(expanded, separation)
        return if (thinned != null) {
            Asked(expanded, thinned, true)
        } else {
            Asked(expanded, index.inBounds(expanded), false)
        }
    }

    @Test
    fun adjacentTilesAgreeOnTheMarkersTheyShare() {
        val all = markers(144_183)
        val index = MarkerGridIndex { all }

        var checked = 0
        var thinnedPairs = 0
        for (z in listOf(9, 10, 11, 12, 13, 14)) {
            val n = Math.pow(2.0, z.toDouble())
            val x = ((139.75 + 180.0) / 360.0 * n).toInt()
            val latRad = 35.68 * Math.PI / 180
            val y = ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()

            // Sideways and downwards. A seam runs in both directions.
            for ((dx, dy) in listOf(1 to 0, 0 to 1)) {
                val a = ask(index, x, y, z)
                val b = ask(index, x + dx, y + dy, z)
                // At low zoom the whole cloud fits on one tile and the
                // neighbour is empty. Comparing against nothing proves nothing.
                if (a.kept.isEmpty() || b.kept.isEmpty()) continue
                checked++
                if (a.thinned) thinnedPairs++

                val inB = b.kept.map { it.state.id }.toSet()
                val missing =
                    a.kept.count {
                        b.expanded.contains(it.state.position) && it.state.id !in inB
                    }
                assertEquals(
                    "z=$z d=($dx,$dy) thinned=${a.thinned}: tile A draws $missing markers " +
                        "that the overlapping tile B does not — the icon is cut at the seam",
                    0,
                    missing,
                )
            }
        }
        assertTrue("too few pairs were comparable", checked >= 8)
        assertTrue("no pair exercised thinning", thinnedPairs >= 4)
    }
}
