package com.mapconductor.core.circle

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.shapetile.ShapeTileRenderer
import com.mapconductor.core.tileserver.TileRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class CircleTileFileDeviceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun renderer() = ShapeTileRenderer(pixelScale = { 1f }, painter = CircleTilePainter)

    private fun state(
        lat: Double,
        lng: Double,
        geodesic: Boolean,
        id: String? = null,
    ) = CircleState(
        id = id, center = GeoPoint(lat, lng), radiusMeters = 250000.0, geodesic = geodesic,
        fillColor = Color(0x802563EB), strokeColor = Color.Red, strokeWidth = 1.dp,
    )

    @Test fun fileAndMemoryProduceIdenticalPixelsAndUpdates() {
        for (geodesic in listOf(false, true)) {
            val data =
                listOf(state(0.0, 0.0, geodesic), state(69.0, -150.0, geodesic), state(42.0, -71.0, geodesic))
                    .map { TileCircle.from(it)!! }
            val memory = renderer().apply { setItems(data) }
            val disk =
                renderer().apply {
                    setStorage(
                        CircleTileFileStorage
                            .create(temporary.root, data.size, { data[it] }),
                    )
                }
            try {
                for ((z, x, y) in listOf(Triple(0, 0, 0), Triple(4, 1, 3), Triple(4, 4, 5))) {
                    fun compare() {
                        val a = memory.renderBitmap(x, y, z)
                        val b = disk.renderBitmap(x, y, z)
                        if (a == null) {
                            assertNull(b)
                        } else {
                            assertNotNull(b)
                            assertTrue("Pixel mismatch at $z/$x/$y geodesic=$geodesic", a.sameAs(b))
                            a.recycle()
                            b!!.recycle()
                        }
                    }
                    compare()
                    val changed = TileCircle.from(state(60.0, -130.0, !geodesic).apply { fillColor = Color.Green })!!
                    memory.update(listOf(1 to changed, 2 to null))
                    disk.update(listOf(1 to changed, 2 to null))
                    compare()
                }
            } finally {
                memory.clear()
                disk.clear()
            }
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun retiringModelWaitsForItsInFlightTileReader() {
        val item = TileCircle.from(state(0.0, 0.0, true))!!
        val storage = CircleTileFileStorage.create(temporary.root, 2001, { item })
        val file = storage.cacheFile
        val renderer = renderer().apply { setStorage(storage) }
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<ByteArray?>()
        val reader =
            thread {
                try {
                    result.set(
                        renderer.renderTile(TileRequest(0, 0, 0)) {
                            entered.countDown()
                            check(resume.await(10, TimeUnit.SECONDS))
                            false
                        },
                    )
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            renderer.clear()
            assertTrue("Old file must remain readable", file.exists())
        } finally {
            resume.countDown()
            reader.join(10000)
            renderer.clear()
        }
        assertFalse(reader.isAlive)
        assertNull(failure.get())
        assertNotNull(result.get())
        assertFalse("Last reader releases the retired file", file.exists())
    }

    @Test fun cancelledRequestReleasesItsFileLease() {
        val storage = CircleTileFileStorage.create(temporary.root, 3, { TileCircle.from(state(0.0, 0.0, true)) })
        val file = storage.cacheFile
        val renderer = renderer().apply { setStorage(storage) }
        try {
            try {
                renderer.renderTile(TileRequest(0, 0, 0)) { true }
                fail("Expected cancellation")
            } catch (
                _: ShapeTileRenderer.TileRenderCancelled,
            ) {
            }
            assertTrue(file.exists())
        } finally {
            renderer.clear()
        }
        assertFalse(file.exists())
    }

    @Test fun automaticallyUsesFilesAbove2000AndDeletesThemOnDispose() =
        runBlocking {
            ResourceProvider.init(
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext,
            )
            val directory = File(ResourceProvider.getAppContext().cacheDir, "mapconductor-circle-geometry")
            val before = directory.listFiles()?.toSet() ?: emptySet<File>()
            val set = CircleTileSet()
            var created = emptySet<File>()
            try {
                val states = List(2001) { state(42.0, -71.0, true, "file-$it").apply { zIndex = it } }
                set.setCircles(states.take(2000))
                assertEquals(before, directory.listFiles()?.toSet() ?: emptySet<File>())
                set.setCircles(states)
                created = directory.listFiles()!!.toSet() - before
                assertEquals(1, created.size)
                set.setCircles(states.filterIndexed { index, _ -> index % 2 == 0 })
                assertFalse(set.has(states[1].id))
                assertTrue(set.has(states[0].id))
            } finally {
                set.dispose()
            }
            assertTrue(created.none { it.exists() })
        }
}
