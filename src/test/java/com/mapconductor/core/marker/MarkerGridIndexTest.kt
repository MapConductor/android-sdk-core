package com.mapconductor.core.marker

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
