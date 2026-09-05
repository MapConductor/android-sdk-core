package com.mapconductor.core.marker

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarkerGridIndex] must answer exactly what a scan would.
 *
 * The index packs a cell key and an array position into one Long and reads them
 * back with shifts and masks, so an off-by-one in the bit layout or in a range
 * bound does not throw — it quietly drops markers. Every test here is the same
 * shape: ask the index, ask brute force, demand the same set.
 */
class MarkerGridIndexTest {
    private fun entity(
        id: Int,
        latitude: Double,
        longitude: Double,
    ): MarkerEntityInterface<Int> =
        MarkerEntity(
            marker = id,
            state = MarkerState(position = GeoPoint.fromLatLong(latitude, longitude), id = "$id"),
        )

    /** A deterministic spread, so a failure is reproducible. */
    private fun scatter(
        count: Int,
        latitude: Double,
        longitude: Double,
        spread: Double,
    ): List<MarkerEntityInterface<Int>> {
        var seed = 0x2545F4914F6CDD1DuL

        fun next(): Double {
            seed = seed * 6364136223846793005uL + 1442695040888963407uL
            return (seed shr 11).toDouble() / (1L shl 53).toDouble()
        }
        return (0 until count).map { index ->
            entity(
                index,
                latitude + (next() - 0.5) * spread,
                longitude + (next() - 0.5) * spread,
            )
        }
    }

    private fun ids(entities: Collection<MarkerEntityInterface<Int>>): Set<String> =
        entities.map { it.state.id }.toSet()

    private fun bounds(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): GeoRectBounds =
        GeoRectBounds(
            southWest = GeoPoint.fromLatLong(south, west),
            northEast = GeoPoint.fromLatLong(north, east),
        )

    @Test
    fun boundsQueryMatchesBruteForce() {
        val markers = scatter(5000, 35.68, 139.76, 0.6)
        val index = MarkerGridIndex { markers }

        // A box inside one cell, one spanning several, one wider than the data,
        // and one over empty ground — the last because an index that returns
        // nothing is right here and wrong everywhere else.
        for (box in listOf(
            bounds(35.6812, 139.7612, 35.6814, 139.7614),
            bounds(35.68, 139.76, 35.69, 139.77),
            bounds(35.0, 139.0, 36.5, 140.5),
            bounds(10.0, 100.0, 11.0, 101.0),
        )) {
            val expected = ids(markers.filter { box.contains(it.state.position) })
            assertEquals("grid disagreed with a scan", expected, ids(index.inBounds(box)))
        }

        // The middle two have to be carrying markers, or the comparison above is
        // only proving that two empty sets match.
        assertFalse(index.inBounds(bounds(35.68, 139.76, 35.69, 139.77)).isEmpty())
        assertEquals(markers.size, index.inBounds(bounds(35.0, 139.0, 36.5, 140.5)).size)
    }

    /**
     * A box crossing 180° has its east corner west of its west one.
     *
     * The first version walked `lonFrom..lonTo` straight, which is an empty
     * range in Kotlin and a trap in Swift. Fiji and the Chathams are real
     * places, and a map centred there is a reversed box every frame.
     */
    @Test
    fun boundsQueryCrossesTheAntimeridian() {
        val markers =
            listOf(
                entity(0, -18.0, 179.9),
                entity(1, -18.0, -179.9),
                entity(2, -18.0, 178.0),
                entity(3, -18.0, 0.0),
            ) + scatter(3000, -18.0, 179.95, 0.4)

        val index = MarkerGridIndex { markers }
        val box = bounds(-18.5, 179.5, -17.5, -179.5)
        val expected = ids(markers.filter { box.contains(it.state.position) })

        assertTrue("the box should hold the marker just west of 180", expected.contains("0"))
        assertTrue("and the one just east of it", expected.contains("1"))
        assertEquals(expected, ids(index.inBounds(box)))
    }

    /**
     * The thinned query may drop markers, but only ones it was told are
     * interchangeable — and never a whole cell.
     *
     * A caller asks for this when it is about to keep one marker per cell of
     * its own anyway. What it must not get back is a hole: a cell holding
     * markers inside the bounds that returns nothing, which on a map is a patch
     * of the city with no trees in it and no error anywhere.
     */
    @Test
    fun thinnedQueryKeepsOneMarkerFromEveryCellItCovers() {
        val markers = scatter(20000, 35.68, 139.76, 0.4)
        val index = MarkerGridIndex { markers }
        val box = bounds(35.60, 139.68, 35.76, 139.84)

        val thinned = index.inBoundsThinned(box, minSeparationDegrees = 0.01)
        assertNotNull("0.01 degrees is coarser than a cell, so this must be answered", thinned)
        val kept = thinned!!

        val inside = markers.filter { box.contains(it.state.position) }
        assertTrue("the box should hold something to compare", inside.size > 1000)

        // Same coupling as the index: a cell is 0.005 degrees a side.
        fun cellOf(entity: MarkerEntityInterface<Int>): Pair<Long, Long> =
            Math.floor(entity.state.position.latitude / 0.005).toLong() to
                Math.floor(entity.state.position.longitude / 0.005).toLong()

        for (entity in kept) {
            assertTrue("returned a marker outside the box", box.contains(entity.state.position))
        }
        assertEquals(
            "one marker per populated cell, no more and no fewer",
            inside.map(::cellOf).toSet(),
            kept.map(::cellOf).toSet(),
        )
        assertEquals("more than one from some cell", kept.map(::cellOf).toSet().size, kept.size)
        // The box is 0.16 degrees a side, so it spans about 1,024 cells and
        // holds around 3,200 of these markers — three to a cell. Without this
        // the assertions above would still pass on data too sparse to thin,
        // and the test would be proving nothing.
        assertTrue("the data is too sparse to be exercising thinning", kept.size * 2 < inside.size)
    }

    /** Cells coarser than the caller's separation would thin more than asked. */
    @Test
    fun thinnedQueryDeclinesWhenItsCellsAreTooCoarse() {
        val index = MarkerGridIndex { scatter(2000, 35.68, 139.76, 0.4) }
        assertNull(index.inBoundsThinned(bounds(35.6, 139.7, 35.7, 139.8), 0.004))
    }

    /** The wrap the plain query learned has to hold here too. */
    @Test
    fun thinnedQueryCrossesTheAntimeridian() {
        val markers =
            listOf(
                entity(0, -18.0, 179.99),
                entity(1, -18.0, -179.99),
                entity(2, -18.0, 178.0),
            )
        val index = MarkerGridIndex { markers }
        val kept = index.inBoundsThinned(bounds(-18.5, 179.5, -17.5, -179.5), 0.01)
        assertNotNull(kept)
        assertEquals(setOf("0", "1"), ids(kept!!))
    }

    @Test
    fun nearestMatchesBruteForce() {
        val markers = scatter(5000, 35.68, 139.76, 0.6)
        val index = MarkerGridIndex { markers }

        for (point in listOf(
            GeoPoint.fromLatLong(35.68, 139.76),
            GeoPoint.fromLatLong(35.4, 139.5),
            GeoPoint.fromLatLong(36.0, 140.1),
        )) {
            val expected = markers.minByOrNull { squared(it, point) }
            assertEquals(expected?.state?.id, index.nearest(point)?.state?.id)
        }
    }

    /**
     * Beyond the rings the index searches it falls back to a scan rather than
     * returning nothing — the answer still has to be right, just slower.
     */
    @Test
    fun nearestFallsBackWhenNothingIsClose() {
        val index = MarkerGridIndex { listOf(entity(0, 35.0, 139.0)) }
        assertEquals("0", index.nearest(GeoPoint.fromLatLong(-35.0, -70.0))?.state?.id)
    }

    @Test
    fun emptyIndexHasNoNearest() {
        val index = MarkerGridIndex<Int> { emptyList() }
        assertNull(index.nearest(GeoPoint.fromLatLong(35.0, 139.0)))
    }

    /** The index borrows the manager's markers, so a change has to be seen. */
    @Test
    fun invalidateShowsLaterMarkers() {
        val markers = mutableListOf(entity(0, 35.0, 139.0))
        val index = MarkerGridIndex { markers }
        val box = bounds(34.9, 138.9, 35.1, 139.1)
        assertEquals(1, index.inBounds(box).size)

        markers.add(entity(1, 35.001, 139.001))
        assertEquals("a stale index should stay stale until told", 1, index.inBounds(box).size)

        index.invalidate()
        assertEquals(2, index.inBounds(box).size)
    }

    private fun squared(
        entity: MarkerEntityInterface<Int>,
        point: GeoPoint,
    ): Double {
        val deltaLat = entity.state.position.latitude - point.latitude
        val deltaLon = entity.state.position.longitude - point.longitude
        return deltaLat * deltaLat + deltaLon * deltaLon
    }
}
