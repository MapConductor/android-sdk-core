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
        val markers = scatter(60000, 35.68, 139.76, 0.4)
        val index = MarkerGridIndex { markers }
        val box = bounds(35.60, 139.68, 35.76, 139.84)

        val thinned = index.inBoundsThinned(box, minSeparationDegrees = 0.005)
        assertNotNull("0.005 degrees is coarser than a cell, so this must be answered", thinned)
        val kept = thinned!!

        val inside = markers.filter { box.contains(it.state.position) }
        assertTrue("the box should hold something to compare", inside.size > 1000)

        // The level the index picks, worked out here the same way. 0.005 degrees
        // gives level 16, an even level, so the cell has a whole-character name
        // of 9 characters and can be compared as a string.
        assertEquals(16L, MarkerGrid.levelForSeparation(0.005)!!.toLong())
        fun cellOf(entity: MarkerEntityInterface<Int>): String =
            MarkerGrid.geocell(
                entity.state.position.latitude,
                entity.state.position.longitude,
                characters = 9,
            )

        // Every cell holding something inside the box has to be represented.
        // Markers from outside the box are fine and expected: a cell's winner
        // is chosen without reference to the box, so a cell on the edge can be
        // represented by a marker just outside it. Not trimming those is what
        // makes neighbouring tiles agree — see `MarkerTileSeamTest`.
        assertTrue(
            "some populated cell returned no representative",
            kept.map(::cellOf).toSet().containsAll(inside.map(::cellOf).toSet()),
        )
        val outside = kept.count { !box.contains(it.state.position) }
        assertTrue(
            "more markers outside the box than one edge cell can explain",
            outside < kept.size * 0.15,
        )
        assertEquals("more than one from some cell", kept.map(::cellOf).toSet().size, kept.size)
        // The box is 0.16 degrees a side, so at level 16 it spans about 3,400
        // square cells of 0.00275 degrees and holds around 9,600 of these
        // markers — nearly three to a cell. Without this the assertions above
        // would still pass on data too sparse to thin, proving nothing.
        assertTrue("the data is too sparse to be exercising thinning", kept.size * 2 < inside.size)
    }

    /**
     * Cells coarser than the caller's separation would thin more than asked.
     *
     * While the grid was flat that floor was 0.005 degrees. Now that it is a
     * hierarchy the floor is the bottom level — 0.000687 degrees on a side —
     * and anything coarser is answered by picking a level. That difference is
     * what makes thinning work at the deeper zooms.
     */
    @Test
    fun thinnedQueryDeclinesWhenItsCellsAreTooCoarse() {
        val index = MarkerGridIndex { scatter(2000, 35.68, 139.76, 0.4) }
        val box = bounds(35.6, 139.7, 35.7, 139.8)
        assertNull(index.inBoundsThinned(box, 0.0005))
        assertNotNull(
            "0.001 degrees is coarser than the bottom level, so a level can be chosen",
            index.inBoundsThinned(box, 0.001),
        )
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

    /**
     * Whatever shape the box is, the index has to answer what a scan answers.
     *
     * Square boxes alone never reach the new level choice's **per-axis cap** —
     * the path a very flat band or a very narrow column takes. Get that wrong
     * and nothing on the map changes except that queries of that shape quietly
     * drop markers.
     *
     * The near-global box is here because column indices fold: the column
     * holding 180 and the one holding -180 are the same, so taking the end of
     * the walk from the east corner covers a single column. See
     * `MarkerGrid.columnWalk`.
     *
     * Pairs with ios-sdk's `testBoundsQueryMatchesBruteForceForEveryShapeOfBox`
     * and react-sdk's test of the same name.
     */
    @Test
    fun boundsQueryMatchesBruteForceForEveryShapeOfBox() {
        // The second group's ids are shifted. `scatter` numbers from zero every
        // time, so adding them straight makes ids collide, and `ids()` folds two
        // different markers into one — the index could return duplicates and the
        // sets would still agree.
        val near = scatter(30000, 35.68, 139.76, 0.8)
        val far =
            scatter(10000, -18.0, 179.95, 0.6).map {
                entity(it.state.id.toInt() + 30000, it.state.position.latitude, it.state.position.longitude)
            }
        val markers = near + far
        val index = MarkerGridIndex { markers }

        var nonEmpty = 0
        val boxes =
            listOf(
                // Tile-sized, from z=14 down to z=9.
                bounds(35.6800, 139.7600, 35.6946, 139.7820),
                bounds(35.6000, 139.6000, 35.7000, 139.8000),
                bounds(35.2000, 139.2000, 36.2000, 140.2000),
                // A very flat band and a very narrow column. Area alone cannot
                // choose a level for either.
                bounds(35.6790, 139.0000, 35.6810, 140.5000),
                bounds(35.0000, 139.7590, 36.4000, 139.7610),
                // Nearly the whole globe.
                bounds(-85.0, -179.9, 85.0, 179.9),
                // Across the antimeridian.
                bounds(-18.5, 179.5, -17.5, -179.5),
                // Empty ground: the one box where returning nothing is right.
                bounds(10.0, 100.0, 11.0, 101.0),
            )
        for ((at, box) in boxes.withIndex()) {
            val expected = ids(markers.filter { box.contains(it.state.position) })
            val found = index.inBounds(box)
            assertEquals("box $at: the grid disagreed with a scan", expected, ids(found))
            assertEquals("box $at: returned the same marker twice", expected.size, found.size)
            if (expected.isNotEmpty()) nonEmpty++
        }
        assertEquals("only comparing empty sets to empty sets", 7, nonEmpty)
    }
}
