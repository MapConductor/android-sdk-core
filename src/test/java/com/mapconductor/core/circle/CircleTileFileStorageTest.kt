package com.mapconductor.core.circle

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.shapetile.ShapeGridIndex
import com.mapconductor.core.shapetile.StorageOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CancellationException

class CircleTileFileStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun state(
        index: Int = 0,
        geodesic: Boolean = true,
    ) = CircleState(
        center = GeoPoint(42.0 + index * 0.00001, -71.0), radiusMeters = 500.0 + index,
        geodesic = geodesic, fillColor = Color(0x802563EB), strokeColor = Color.Red, strokeWidth = 0.5.dp,
    )

    @Test fun exactGeometryRoundTripAcrossBlockBoundaries() {
        val storage =
            CircleTileFileStorage.create(temporary.root, 1025, {
                if (it == 511) null else TileCircle.from(state(it, it % 2 == 0))
            })
        try {
            assertEquals(16L + 80L * 1025, storage.cacheFile.length())
            for (index in listOf(0, 510, 512, 1023, 1024)) {
                val source = state(index, index % 2 == 0)
                val decoded = storage[index]!!
                assertTrue(decoded.matches(source))
                assertArrayEquals(TileCircle.from(source)!!.bbox, storage.bbox(index), 0.0)
            }
            assertNull(storage[511])
            assertNull(storage.bbox(511))
            assertNull(storage[-1])
            assertNull(storage[1025])
        } finally {
            storage.release()
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun blockCacheRemainsBoundedAndEvictedRecordsReload() {
        val storage = CircleTileFileStorage.create(temporary.root, 512 * 60, { TileCircle.from(state(it / 512)) })
        try {
            for (block in 0 until 60) assertTrue(storage[block * 512]!!.matches(state(block)))
            assertTrue(storage.cachedBytes <= 48 * 512 * 80)
            assertTrue(storage[0]!!.matches(state()))
            assertEquals(48, storage.allocatedReadBuffers)
        } finally {
            storage.release()
        }
    }

    @Test fun snapshotsShareFilesWithoutSharingUpdatedValuesOrReaderLifetimes() {
        val before = CircleTileFileStorage.create(temporary.root, 3, { TileCircle.from(state(it)) })
        val path = before.cacheFile
        before.retain() // A tile request that outlives replacement of the model.
        val changed = TileCircle.from(state(40, false))!!
        val after = before.updated(listOf(0 to changed, 1 to null))
        before.release() // Retire the old renderer owner.
        try {
            assertTrue(before[0]!!.matches(state()))
            assertTrue(after[0]!!.matches(state(40, false)))
            assertNull(after[1])
            assertNotNull(before[1])
            after.release()
            assertTrue(path.exists())
        } finally {
            before.release()
        }
        assertFalse(path.exists())
    }

    @Test fun largeUpdateCompactsWithoutModifyingOldSnapshots() {
        val before = CircleTileFileStorage.create(temporary.root, 2100, { TileCircle.from(state()) })
        val oldPath = before.cacheFile
        val changed = TileCircle.from(state(20, false))!!
        val after = before.updated(List(2050) { it to if (it == 10) null else changed }) as CircleTileFileStorage
        try {
            assertNotEquals(oldPath, after.cacheFile)
            assertTrue(before[0]!!.matches(state()))
            assertTrue(after[0]!!.matches(state(20, false)))
            assertNull(after[10])
            assertTrue(after[2099]!!.matches(state()))
            before.release()
            assertFalse(oldPath.exists())
            assertNotNull(after[1])
        } finally {
            after.release()
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun updatingExistingSlotsAtTheOverrideLimitStillSharesTheFile() {
        val before = CircleTileFileStorage.create(temporary.root, 3000, { TileCircle.from(state()) })
        val changed = TileCircle.from(state(30, false))!!
        val atLimit = before.updated(List(2048) { it to changed }) as CircleTileFileStorage
        val after = atLimit.updated(listOf(0 to null, 1 to changed)) as CircleTileFileStorage
        try {
            assertEquals(before.cacheFile, after.cacheFile)
            assertNull(after[0])
            assertNotNull(atLimit[0])
            assertTrue(after[1]!!.matches(state(30, false)))
        } finally { before.release(); atLimit.release(); after.release() }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun cancelledOrFailedWritesDeletePartialCacheFiles() {
        val active = CircleTileFileStorage.create(temporary.root, 2, { TileCircle.from(state()) })
        try {
            for (cancel in listOf(false, true)) {
                try {
                    CircleTileFileStorage.create(temporary.root, 10000, {
                        if (!cancel && it == 600) throw IOException("simulated write source failure")
                        TileCircle.from(state())
                    }, { if (cancel) throw CancellationException("cancelled") })
                    fail("Expected write failure")
                } catch (_: IOException) {
                } catch (_: CancellationException) {
                }
                assertEquals(listOf(active.cacheFile), temporary.root.listFiles()!!.toList())
                assertNotNull(active[0])
            }
        } finally {
            active.release()
        }
    }

    /** Every fourth one here, the rest far away and spread out: in index order the ones here span every block. */
    private fun scattered(count: Int) =
        List(count) { i ->
            if (i % 4 == 0) {
                CircleState(center = GeoPoint(42.0 + i * 0.000001, -71.0), radiusMeters = 100.0 + i, fillColor = Color.Blue)
            } else {
                CircleState(center = GeoPoint(30.0 + (i % 50), -120.0 + (i % 97) * 0.5), radiusMeters = 100.0 + i, fillColor = Color.Red)
            }
        }

    private fun byPlace(states: List<CircleState>) =
        StorageOrder.sortedBy(states.size) { StorageOrder.mortonCellKey(states[it].center.latitude, states[it].center.longitude) }

    @Test fun storedByPlaceReadsAndIndexesByTheListsIndices() {
        val states = scattered(2048)
        val order = byPlace(states)
        val storage = CircleTileFileStorage.create(temporary.root, states.size, { TileCircle.from(states[it]) }, order = order)
        try {
            assertEquals(order, storage.storageOrder)
            // The ones here, before anything else is read: contiguous in storage,
            // so at most the two blocks they straddle (all four in index order),
            // and each call says which it is.
            val here = IntArray(512) { it * 4 }
            val seen = BooleanArray(here.size)
            storage.forEachRecord(here, {}) { k, bytes, offset, _ ->
                seen[k] = true
                assertEquals(TileCircle.from(states[here[k]])!!.bbox[0], bytes!!.getDouble(offset + 24) - bytes.getDouble(offset + 40), 1e-12)
            }
            assertTrue(seen.all { it })
            assertTrue("blocks read: ${storage.allocatedReadBuffers}", storage.allocatedReadBuffers <= 2)
            val plain = CircleTileFileStorage.create(temporary.newFolder(), states.size, { TileCircle.from(states[it]) })
            try {
                plain.forEachRecord(here, {}) { _, _, _, _ -> }
                assertEquals("blocks read in index order", 4, plain.allocatedReadBuffers)
            } finally {
                plain.release()
            }
            for (i in states.indices) {
                assertTrue("index $i", storage[i]!!.matches(states[i]))
                assertArrayEquals(TileCircle.from(states[i])!!.bbox, storage.bbox(i), 0.0)
            }
        } finally {
            storage.release()
        }
    }

    @Test fun storedByPlaceKeepsIndicesThroughUpdatesAndCompaction() {
        val states = scattered(4096)
        val storage = CircleTileFileStorage.create(temporary.root, states.size, { TileCircle.from(states[it]) }, order = byPlace(states))
        // More than the override limit: compacts into a new file in the same order.
        val changed = (0 until 3000).map { it to TileCircle.from(CircleState(center = GeoPoint(10.0, 10.0 + it * 0.001), radiusMeters = 7.0)) }
        val after = storage.updated(changed)
        storage.release()
        try {
            assertNotEquals(null, after.storageOrder)
            for (i in 0 until 3000) assertEquals("index $i", 7.0, after[i]!!.radiusMeters, 0.0)
            for (i in 3000 until 4096) assertTrue("index $i", after[i]!!.matches(states[i]))
        } finally {
            after.release()
        }
    }

    @Test fun theGridAnswersTheSameInStorageOrder() {
        val states = scattered(3000)
        val boxes = states.map { TileCircle.from(it)!!.bbox }
        val order = byPlace(states)
        val plain = ShapeGridIndex.build(boxes.size) { boxes[it] }
        val read = ArrayList<Int>()
        val ordered = ShapeGridIndex.build(boxes.size, order) { read.add(it); boxes[it] }
        assertEquals("built in storage order", order.indexAt.toList(), read)
        for ((x, y, size) in listOf(Triple(0.3, 0.37, 0.001), Triple(0.0, 0.0, 1.0), Triple(0.16, 0.38, 0.05))) {
            val inspected = ArrayList<Int>()
            val answer = ordered.query({ inspected.add(it); boxes[it] }, x, y, x + size, y + size, order)
            assertArrayEquals(plain.query({ boxes[it] }, x, y, x + size, y + size), answer)
            assertEquals("boxes read in storage order", inspected.sortedBy { order.positionOf[it] }, inspected)
        }
    }

    @Test fun mortonKeysAndTheOrderAreStable() {
        // Cell (2048, 2048): bit 11 of x and of y.
        assertEquals((1 shl 22) or (1 shl 23), StorageOrder.mortonCellKey(0.0, 0.0))
        val order = StorageOrder.sortedBy(6) { intArrayOf(5, 1, 5, 0, 1, 5)[it] }
        assertArrayEquals(intArrayOf(3, 1, 4, 0, 2, 5), order.indexAt)
        assertArrayEquals(intArrayOf(3, 1, 4, 0, 2, 5).let { a -> IntArray(6).also { p -> a.forEachIndexed { pos, i -> p[i] = pos } } }, order.positionOf)
    }
}
