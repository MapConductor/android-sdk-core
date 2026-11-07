package com.mapconductor.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface
import com.mapconductor.core.polygon.OnPolygonEventHandler
import com.mapconductor.core.polygon.PolygonCapableInterface
import com.mapconductor.core.polygon.PolygonController
import com.mapconductor.core.polygon.PolygonEntityInterface
import com.mapconductor.core.polygon.PolygonManager
import com.mapconductor.core.polygon.PolygonOverlay
import com.mapconductor.core.polygon.PolygonOverlayRendererInterface
import com.mapconductor.core.polygon.PolygonOverlayRendererInterface.AddParamsInterface
import com.mapconductor.core.polygon.PolygonOverlayRendererInterface.ChangeParamsInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polygon.PolygonTiler
import com.mapconductor.core.polygon.PolygonTilingOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `onAdded` on the tile path: the real core PolygonController with a real
 * PolygonTiler (its renderer needs android.util.LruCache, hence a device
 * test). The native path and the rest are in the JVM OverlayOnAddedTest.
 */
@RunWith(AndroidJUnit4::class)
class OverlayOnAddedDeviceTest {
    private val events = mutableListOf<String>()

    private inner class FakeRenderer : PolygonOverlayRendererInterface<String> {
        override suspend fun onAdd(data: List<AddParamsInterface>): List<String?> =
            data.map {
                events += "native:${it.state.id}"
                "native-${it.state.id}"
            }

        override suspend fun onChange(data: List<ChangeParamsInterface<String>>): List<String?> = data.map { it.prev.polygon }

        override suspend fun onRemove(data: List<PolygonEntityInterface<String>>) {
            data.forEach { events += "remove:${it.state.id}" }
        }

        override suspend fun onPostProcess() = Unit
    }

    private inner class FakeMap(
        tiling: PolygonTilingOptions,
    ) : MapViewControllerInterface,
        PolygonCapableInterface {
        val polygons = object : PolygonController<String>(PolygonManager(), FakeRenderer()) {}

        init {
            polygons.tiler =
                PolygonTiler(tiling).also { tiler ->
                    // What a provider does: upsert the raster layer, or remove it.
                    tiler.onTemplate = { template, _ -> events += if (template != null) "upsert" else "removeLayer" }
                }
        }

        override suspend fun compositionPolygons(data: List<PolygonState>) = polygons.add(data)

        override suspend fun updatePolygon(state: PolygonState) = polygons.update(state)

        @Deprecated("Use PolygonState.onClick instead.")
        override fun setOnPolygonClickListener(listener: OnPolygonEventHandler?) = Unit

        override fun hasPolygon(state: PolygonState): Boolean = polygons.has(state.id)

        override val holder: MapViewHolderInterface<*, *> get() = error("not used")

        override suspend fun clearOverlays() = polygons.clear()

        override fun moveCamera(position: MapCameraPosition) = Unit

        override fun animateCamera(
            position: MapCameraPosition,
            duration: Long,
        ) = Unit

        override fun fitBounds(
            bounds: GeoRectBounds,
            padding: Int,
        ) = Unit

        override fun registerOverlayController(controller: OverlayControllerInterface<*, *>) = Unit

        override fun destroy() = polygons.destroy()
    }

    private val maps = mutableListOf<FakeMap>()

    @After
    fun tearDown() = maps.forEach { it.destroy() }

    private fun tiling(minPolygonCount: Int) = PolygonTilingOptions(minPolygonCount = minPolygonCount)

    private fun map(minPolygonCount: Int) = FakeMap(tiling(minPolygonCount)).also { maps += it }

    private val recorder: (PolygonState) -> Unit = { events += "onAdded:${it.id}" }

    private fun square(i: Int): PolygonState {
        val lat = (i / 100) * 0.01
        val lng = (i % 100) * 0.01
        val points: List<GeoPointInterface> =
            listOf(
                GeoPoint.fromLatLong(lat, lng),
                GeoPoint.fromLatLong(lat, lng + 0.005),
                GeoPoint.fromLatLong(lat + 0.005, lng + 0.005),
                GeoPoint.fromLatLong(lat + 0.005, lng),
            )
        return PolygonState(points = points, id = "p$i", onAdded = recorder)
    }

    private fun PolygonOverlay.show(
        map: FakeMap,
        states: List<PolygonState>,
    ) = runBlocking { render(states.associateBy { it.id }.toMutableMap(), map) }

    private fun overlay() = PolygonOverlay(MutableStateFlow(mutableMapOf()))

    @Test
    fun calledAfterTheTileTemplateWasHandedToTheMap() {
        val map = map(minPolygonCount = 5)
        val states = List(5) { square(it) }

        overlay().show(map, states)

        assertEquals("upsert", events.first())
        assertEquals(states.map { "onAdded:${it.id}" }, events.drop(1))
        assertTrue("nothing drawn natively", events.none { it.startsWith("native:") })
    }

    @Test
    fun anInPlaceChangeOfATiledPolygonCallsNothing() {
        val map = map(minPolygonCount = 2)
        val overlay = overlay()
        val states = List(2) { square(it) }
        overlay.show(map, states)
        events.clear()

        states[0].fillColor = androidx.compose.ui.graphics.Color.Red
        runBlocking { map.updatePolygon(states[0]) }
        overlay.show(map, states)

        assertTrue(events.toString(), events.none { it.startsWith("onAdded") })
    }

    @Test
    fun movingBetweenNativeAndTilesWhileOnTheMapCallsNothing() {
        val map = map(minPolygonCount = 4)
        val overlay = overlay()
        val first = List(3) { square(it) }
        overlay.show(map, first)
        assertEquals(listOf("onAdded:p0", "onAdded:p1", "onAdded:p2"), events.filter { it.startsWith("onAdded") })
        events.clear()

        // A fourth polygon tips the set into tiles: only the new one is added.
        overlay.show(map, first + square(3))
        assertEquals(listOf("upsert", "remove:p0", "remove:p1", "remove:p2", "onAdded:p3"), events)
        events.clear()

        // Back to native drawing: p0..p2 were on the map all along.
        overlay.show(map, first)
        assertEquals(listOf("removeLayer", "native:p0", "native:p1", "native:p2"), events)
        events.clear()

        // p3 left; adding it again (into tiles) calls it again.
        overlay.show(map, first + square(3))
        assertEquals("onAdded:p3", events.last())
        assertEquals(1, events.count { it.startsWith("onAdded") })
    }
}
