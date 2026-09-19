package com.mapconductor.core.geocell

import com.mapconductor.core.projection.ProjectedPoint
import com.mapconductor.core.features.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The unordered radius search returns the same cells as the sorted one.
 *
 * It exists because sorting dominated bounds queries — 42 ms of a 64 ms query
 * at 20k markers — and bounds queries throw the distances away. Its traversal
 * was written out separately, so this pins the two against each other rather
 * than trusting that the pruning was copied correctly.
 */
class KDTreeRadiusTest {
    private fun cells(count: Int): List<HexCell> {
        var seed = 42L

        fun next(): Double {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 40).toDouble() / 1000.0)
        }
        return (0 until count).map { index ->
            HexCell(
                coord = HexCoord(index, index),
                centerLatLng = GeoPoint(0.0, 0.0),
                centerXY = ProjectedPoint(next(), next()),
                id = "cell-$index",
            )
        }
    }

    @Test
    fun unorderedMatchesSorted() {
        val tree = KDTree(cells(2_000))
        val query = ProjectedPoint(8_000.0, 8_000.0)

        // A spread of radii: one that excludes everything, several partial, and
        // one that takes the lot. A traversal bug that only shows at the
        // boundary would survive a single radius.
        for (radius in listOf(0.0, 1.0, 50.0, 500.0, 5_000.0, 20_000.0)) {
            val sorted = tree.withinRadiusWithDistance(query, radius).map { it.cell.id }.toSet()
            val unordered = tree.withinRadius(query, radius).map { it.id }.toSet()
            assertEquals("radius $radius", sorted, unordered)
        }
    }
}
