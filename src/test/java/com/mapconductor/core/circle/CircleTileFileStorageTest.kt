package com.mapconductor.core.circle

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mapconductor.core.features.GeoPoint
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
}
