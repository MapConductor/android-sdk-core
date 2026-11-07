package com.mapconductor.core

import com.mapconductor.core.circle.CircleCapableInterface
import com.mapconductor.core.circle.CircleOverlay
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageCapableInterface
import com.mapconductor.core.groundimage.GroundImageOverlay
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.OnGroundImageEventHandler
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface
import com.mapconductor.core.marker.MarkerAnimationOverlayHost
import com.mapconductor.core.marker.MarkerCapableInterface
import com.mapconductor.core.marker.MarkerOverlay
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.OnMarkerEventHandler
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
import com.mapconductor.core.polyline.OnPolylineEventHandler
import com.mapconductor.core.polyline.PolylineCapableInterface
import com.mapconductor.core.polyline.PolylineOverlay
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.raster.RasterLayerCapableInterface
import com.mapconductor.core.raster.RasterLayerOverlay
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `onAdded` of the overlay states.
 *
 * Driven the way a map drives it: the overlay hands its states to the
 * controller (`compositionXxx`), and `onAdded` runs for the states the map now
 * has that it did not have before. The polygon controller here is the real
 * core one, so the native path (renderer.onAdd) is the code providers run.
 * The tile path needs android.util.LruCache (PolygonTileRenderer) and is in
 * androidTest: OverlayOnAddedDeviceTest.
 */
class OverlayOnAddedTest {
    private val events = mutableListOf<String>()

    /** A polygon renderer that draws nothing and records what it was asked. */
    private inner class FakeRenderer : PolygonOverlayRendererInterface<String> {
        var failIds: Set<String> = emptySet()

        override suspend fun onAdd(data: List<AddParamsInterface>): List<String?> =
            data.map { params ->
                val id = params.state.id
                if (id in failIds) {
                    null
                } else {
                    events += "native:$id"
                    "native-$id"
                }
            }

        override suspend fun onChange(data: List<ChangeParamsInterface<String>>): List<String?> =
            data.map {
                events += "change:${it.current.state.id}"
                it.prev.polygon
            }

        override suspend fun onRemove(data: List<PolygonEntityInterface<String>>) {
            data.forEach { events += "remove:${it.state.id}" }
        }

        override suspend fun onPostProcess() = Unit
    }

    private inner class FakePolygonController(
        renderer: FakeRenderer,
    ) : PolygonController<String>(PolygonManager(), renderer)

    /** Just enough of a map: polygons go through the real core controller. */
    private inner class FakeMap :
        MapViewControllerInterface,
        PolygonCapableInterface {
        val renderer = FakeRenderer()
        val polygons = FakePolygonController(renderer)

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

        override fun destroy() {
            polygons.destroy()
        }
    }

    private val maps = mutableListOf<FakeMap>()

    @After
    fun tearDown() {
        maps.forEach { it.destroy() }
    }

    private fun map() = FakeMap().also { maps += it }

    private fun square(
        i: Int,
        onAdded: ((PolygonState) -> Unit)? = null,
    ): PolygonState {
        val lat = (i / 100) * 0.01
        val lng = (i % 100) * 0.01
        val points: List<GeoPointInterface> =
            listOf(
                GeoPoint.fromLatLong(lat, lng),
                GeoPoint.fromLatLong(lat, lng + 0.005),
                GeoPoint.fromLatLong(lat + 0.005, lng + 0.005),
                GeoPoint.fromLatLong(lat + 0.005, lng),
            )
        return PolygonState(points = points, id = "p$i", onAdded = onAdded)
    }

    private val recorder: (PolygonState) -> Unit = { events += "onAdded:${it.id}" }

    private fun PolygonOverlay.show(
        map: FakeMap,
        states: List<PolygonState>,
    ) = runBlocking { render(states.associateBy { it.id }.toMutableMap(), map) }

    private fun overlay() = PolygonOverlay(MutableStateFlow(mutableMapOf()))

    @Test
    fun `native drawing - called once each, after the renderer made the object`() {
        val map = map()
        val overlay = overlay()
        val states = List(3) { square(it, recorder) }

        overlay.show(map, states)

        assertEquals(
            listOf("native:p0", "native:p1", "native:p2", "onAdded:p0", "onAdded:p1", "onAdded:p2"),
            events,
        )
    }

    @Test
    fun `an in-place update or the same list again calls nothing`() {
        val map = map()
        val overlay = overlay()
        val states = List(2) { square(it, recorder) }
        overlay.show(map, states)
        events.clear()

        states[0].fillColor = androidx.compose.ui.graphics.Color.Red
        runBlocking { map.updatePolygon(states[0]) }
        overlay.show(map, states)
        // Replacing the callback redraws nothing and calls nothing either.
        states[1].onAdded = { events += "replaced:${it.id}" }
        overlay.show(map, states)

        assertEquals(listOf("change:p0"), events)
    }

    @Test
    fun `removed and added again - called again`() {
        val map = map()
        val overlay = overlay()
        val a = square(0, recorder)
        val b = square(1, recorder)
        overlay.show(map, listOf(a, b))
        overlay.show(map, listOf(a))
        events.clear()

        overlay.show(map, listOf(a, b))

        assertEquals(listOf("native:p1", "onAdded:p1"), events)
    }

    @Test
    fun `a callback that throws does not stop the others`() {
        val map = map()
        val overlay = overlay()
        val states =
            listOf(
                square(0, recorder),
                square(1) { throw IllegalStateException("app bug") },
                square(2, recorder),
            )

        overlay.show(map, states)

        assertEquals(listOf("onAdded:p0", "onAdded:p2"), events.filter { it.startsWith("onAdded") })
    }

    @Test
    fun `not called for what the map could not add, until it does`() {
        val map = map()
        val overlay = overlay()
        map.renderer.failIds = setOf("p1")
        val states = List(2) { square(it, recorder) }

        overlay.show(map, states)
        assertEquals(listOf("onAdded:p0"), events.filter { it.startsWith("onAdded") })

        events.clear()
        map.renderer.failIds = emptySet()
        overlay.show(map, states)
        assertEquals(listOf("native:p1", "onAdded:p1"), events)
    }

    @Test
    fun `clearing the map calls nothing, and a new map starts from nothing`() {
        val overlay = overlay()
        val states = List(2) { square(it, recorder) }
        val first = map()
        overlay.show(first, states)
        runBlocking { first.clearOverlays() }
        assertEquals(2, events.count { it.startsWith("onAdded") })

        events.clear()
        overlay.show(map(), states)
        assertEquals(listOf("onAdded:p0", "onAdded:p1"), events.filter { it.startsWith("onAdded") })
    }

    // ── the other overlay types use the same tracker; check each is wired ──

    private class RecordingMap :
        MapViewControllerInterface,
        MarkerCapableInterface,
        CircleCapableInterface,
        PolylineCapableInterface,
        GroundImageCapableInterface,
        RasterLayerCapableInterface {
        val ids = mutableSetOf<String>()

        override suspend fun compositionMarkers(data: List<MarkerState>) = data.forEach { ids += it.id }

        override suspend fun updateMarker(state: MarkerState) = Unit

        override fun hasMarker(state: MarkerState) = state.id in ids

        @Deprecated("x")
        override fun setOnMarkerDragStart(listener: OnMarkerEventHandler?) = Unit

        @Deprecated("x")
        override fun setOnMarkerDrag(listener: OnMarkerEventHandler?) = Unit

        @Deprecated("x")
        override fun setOnMarkerDragEnd(listener: OnMarkerEventHandler?) = Unit

        @Deprecated("x")
        override fun setOnMarkerAnimateStart(listener: OnMarkerEventHandler?) = Unit

        @Deprecated("x")
        override fun setOnMarkerAnimateEnd(listener: OnMarkerEventHandler?) = Unit

        @Deprecated("x")
        override fun setOnMarkerClickListener(listener: OnMarkerEventHandler?) = Unit

        override fun setMarkerAnimationOverlayHost(host: MarkerAnimationOverlayHost?) = Unit

        override suspend fun compositionCircles(data: List<CircleState>) = data.forEach { ids += it.id }

        override suspend fun updateCircle(state: CircleState) = Unit

        @Deprecated("x")
        override fun setOnCircleClickListener(listener: OnCircleEventHandler?) = Unit

        override fun hasCircle(state: CircleState) = state.id in ids

        override suspend fun compositionPolylines(data: List<PolylineState>) = data.forEach { ids += it.id }

        override suspend fun updatePolyline(state: PolylineState) = Unit

        @Deprecated("x")
        override fun setOnPolylineClickListener(listener: OnPolylineEventHandler?) = Unit

        override fun hasPolyline(state: PolylineState) = state.id in ids

        override suspend fun compositionGroundImages(data: List<GroundImageState>) = data.forEach { ids += it.id }

        override suspend fun updateGroundImage(state: GroundImageState) = Unit

        @Deprecated("x")
        override fun setOnGroundImageClickListener(listener: OnGroundImageEventHandler?) = Unit

        override fun hasGroundImage(state: GroundImageState) = state.id in ids

        override suspend fun compositionRasterLayers(data: List<RasterLayerState>) = data.forEach { ids += it.id }

        override suspend fun updateRasterLayer(state: RasterLayerState) = Unit

        override fun hasRasterLayer(state: RasterLayerState) = state.id in ids

        override val holder: MapViewHolderInterface<*, *> get() = error("not used")

        override suspend fun clearOverlays() = Unit

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

        override fun destroy() = Unit
    }

    @Test
    fun `markers, circles, polylines, ground images and raster layers call onAdded too`() {
        val map = RecordingMap()
        val here = GeoPoint.fromLatLong(35.0, 139.0)
        val marker = MarkerState(position = here, id = "m", onAdded = { events += "marker:${it.id}" })
        val circle =
            CircleState(center = here, radiusMeters = 10.0, id = "c", onAdded = { events += "circle:${it.id}" })
        val polyline =
            PolylineState(
                points = listOf(here, GeoPoint.fromLatLong(35.1, 139.1)),
                id = "l",
                onAdded = { events += "polyline:${it.id}" },
            )
        val groundImage =
            GroundImageState(
                bounds =
                    GeoRectBounds().apply {
                        extend(here)
                        extend(GeoPoint.fromLatLong(35.1, 139.1))
                    },
                image = FakeDrawable(),
                id = "g",
                onAdded = { events += "groundImage:${it.id}" },
            )
        val raster =
            RasterLayerState(
                source = RasterLayerSource.UrlTemplate(template = "https://example.com/{z}/{x}/{y}.png"),
                id = "r",
                onAdded = { events += "raster:${it.id}" },
            )
        runBlocking {
            MarkerOverlay(MutableStateFlow(mutableMapOf())).render(mutableMapOf("m" to marker), map)
            CircleOverlay(MutableStateFlow(mutableMapOf())).render(mutableMapOf("c" to circle), map)
            PolylineOverlay(MutableStateFlow(mutableMapOf())).render(mutableMapOf("l" to polyline), map)
            GroundImageOverlay(MutableStateFlow(mutableMapOf())).render(mutableMapOf("g" to groundImage), map)
            RasterLayerOverlay(MutableStateFlow(mutableMapOf())).render(mutableMapOf("r" to raster), map)
        }
        assertEquals(listOf("marker:m", "circle:c", "polyline:l", "groundImage:g", "raster:r"), events)
    }

    /** GroundImageState needs a Drawable; nothing here draws it. */
    private class FakeDrawable : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) = Unit

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    @Test
    fun `copy carries onAdded, and it is not part of equality`() {
        val callback: (PolygonState) -> Unit = {}
        val a = square(0, callback)
        assertTrue(a.copy().onAdded === callback)
        assertEquals(a, square(0, null))
        assertEquals(a.fingerPrint(), square(0, null).fingerPrint())
        val marker = MarkerState(position = GeoPoint.fromLatLong(0.0, 0.0), id = "m", onAdded = { })
        assertTrue(marker.copy().onAdded === marker.onAdded)
        assertEquals(marker.fingerPrint(), marker.copy(onAdded = null).fingerPrint())
    }
}
