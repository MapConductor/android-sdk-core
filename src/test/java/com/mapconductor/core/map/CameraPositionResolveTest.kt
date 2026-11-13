package com.mapconductor.core.map

import com.mapconductor.core.features.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未指定の zoom / bearing / tilt だけが現在のカメラから引き継がれ、
 * 明示された 0 はそのまま使われることを確認する。
 *
 * ios-sdk の `CameraPositionResolveTests`、js-sdk の `CameraPositionResolve.test.mjs` と同じ観点。
 */
class CameraPositionResolveTest {
    private val tokyo = GeoPoint(latitude = 35.6812, longitude = 139.7671)
    private val osaka = GeoPoint(latitude = 34.7025, longitude = 135.4959)
    private val current = MapCameraPosition(position = tokyo, zoom = 12.0, bearing = 30.0, tilt = 45.0)

    private fun MapCameraPosition.zbt() = listOf(zoom, bearing, tilt)

    @Test
    fun positionOnlyKeepsEverythingElse() {
        val resolved = MapCameraPosition(position = osaka).resolvedOnto(current)
        assertEquals(osaka.latitude, resolved.position.latitude, 0.0)
        assertEquals(listOf(12.0, 30.0, 45.0), resolved.zbt())
    }

    @Test
    fun explicitZeroIsHonoured() {
        val resolved =
            MapCameraPosition(position = osaka, zoom = 0.0, bearing = 0.0, tilt = 0.0)
                .resolvedOnto(current)
        assertEquals(listOf(0.0, 0.0, 0.0), resolved.zbt())
    }

    @Test
    fun eachPropertyIsInheritedOnItsOwn() {
        assertEquals(
            listOf(0.0, 30.0, 45.0),
            MapCameraPosition(position = osaka, zoom = 0.0).resolvedOnto(current).zbt(),
        )
        assertEquals(
            listOf(12.0, 0.0, 45.0),
            MapCameraPosition(position = osaka, bearing = 0.0).resolvedOnto(current).zbt(),
        )
        assertEquals(
            listOf(12.0, 30.0, 0.0),
            MapCameraPosition(position = osaka, tilt = 0.0).resolvedOnto(current).zbt(),
        )
    }

    @Test
    fun unsetReadsAsZeroBeforeResolving() {
        val camera = MapCameraPosition(position = osaka)
        assertEquals(0.0, camera.zoom, 0.0)
        assertFalse(camera.isZoomSpecified)
        assertFalse(camera.isBearingSpecified)
        assertFalse(camera.isTiltSpecified)
        assertTrue(MapCameraPosition.Default.isZoomSpecified)
    }

    @Test
    fun copyKeepsUnspecifiedUnlessGiven() {
        assertFalse(MapCameraPosition(position = osaka).copy(position = tokyo).isZoomSpecified)

        val withZoom = MapCameraPosition(position = osaka).copy(zoom = 0.0)
        assertTrue(withZoom.isZoomSpecified)
        assertFalse(withZoom.isBearingSpecified)
    }

    private class TestState(
        initial: MapCameraPosition,
    ) : MapViewState<String>(initial) {
        override val id: String = "test"
        override var mapDesignType: String = "design"
    }

    @Test
    fun mapViewStateMergesUnsetPropertiesFromCurrentCamera() {
        val state = TestState(current)

        state.moveCameraTo(MapCameraPosition(position = osaka, bearing = 0.0), null)
        assertEquals(listOf(12.0, 0.0, 45.0), state.cameraPosition.zbt())

        state.moveCameraTo(MapCameraPosition(position = osaka, zoom = 0.0), null)
        assertEquals(0.0, state.cameraPosition.zoom, 0.0)
    }
}
