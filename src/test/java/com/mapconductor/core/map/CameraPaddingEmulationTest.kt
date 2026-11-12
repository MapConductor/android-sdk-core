package com.mapconductor.core.map

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.spherical.Spherical
import com.mapconductor.core.zoom.AbstractZoomAltitudeConverter
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * パディングのエミュレーションが、独立に書いたピンホールカメラの投影と一致することを確認する。
 *
 * ios-sdk の `CameraPaddingEmulationTests`、js-sdk の `CameraPaddingEmulation.test.mjs` と同じ観点。
 */
@OptIn(InternalMapConductorApi::class)
class CameraPaddingEmulationTest {
    private val target = GeoPoint(latitude = 35.6812, longitude = 139.7671)
    private val zoom = 15.0
    private val height = 800.0

    private fun metersPerDp(latitude: Double) =
        AbstractZoomAltitudeConverter.WEB_MERCATOR_INITIAL_MPP_256 * cos(Math.toRadians(latitude)) / 2.0.pow(zoom)

    /**
     * カメラ中心 [center] から見た [point] の画面位置（中央からの dp、右・下が正）。
     * 地上の (前方, 右) をピンホールカメラで投影する。fov 0 は遠近なし。
     */
    private fun project(
        center: GeoPointInterface,
        point: GeoPointInterface,
        tilt: Double,
        heading: Double,
        fov: Double,
    ): Pair<Double, Double> {
        val d = Spherical.computeDistanceBetween(center, point)
        val dir = Math.toRadians(Spherical.computeHeading(center, point) - heading)
        val forward = d * cos(dir)
        val right = d * sin(dir)
        val mpp = metersPerDp(center.latitude)
        val t = Math.toRadians(tilt)
        if (fov == 0.0) return Pair(right / mpp, -forward * cos(t) / mpp)
        val f = (height / 2) / tan(Math.toRadians(fov) / 2)
        val distance = f * mpp
        // カメラ座標: 光軸方向の奥行き z、画面下向き y。
        val z = distance + forward * sin(t)
        val y = -forward * cos(t)
        return Pair(f * right / z, f * y / z)
    }

    private fun assertLandsAtPaddedCenter(
        paddings: MapPaddings,
        tilt: Double,
        heading: Double,
        fov: Double,
    ) {
        val center =
            CameraPaddingEmulation.centerForTarget(target, zoom, tilt, heading, paddings, fov, height)
        val (x, y) = project(center, target, tilt, heading, fov)
        assertEquals("x tilt=$tilt heading=$heading fov=$fov", (paddings.left - paddings.right) / 2, x, 0.5)
        assertEquals("y tilt=$tilt heading=$heading fov=$fov", (paddings.top - paddings.bottom) / 2, y, 0.5)
        val back = CameraPaddingEmulation.targetForCenter(center, zoom, tilt, heading, paddings, fov, height)
        assertEquals(target.latitude, back.latitude, 5e-6)
        assertEquals(target.longitude, back.longitude, 5e-6)
    }

    @Test
    fun noPaddingKeepsTarget() {
        val center =
            CameraPaddingEmulation.centerForTarget(target, zoom, 60.0, 0.0, MapPaddings.Zeros, 36.87, height)
        assertEquals(target.latitude, center.latitude, 0.0)
        assertEquals(target.longitude, center.longitude, 0.0)
    }

    @Test
    fun topPaddingAtTiltZeroMovesCenterNorth() {
        val center =
            CameraPaddingEmulation.centerForTarget(target, zoom, 0.0, 0.0, MapPaddings(top = 300.0), 36.87, height)
        val meters = Spherical.computeDistanceBetween(target, center)
        assertEquals(150 * metersPerDp(target.latitude), meters, 0.05)
        assertEquals(0.0, Spherical.computeHeading(target, center), 0.01)
    }

    @Test
    fun perspectiveLandsAtPaddedCenter() {
        for (tilt in listOf(0.0, 30.0, 45.0, 60.0)) {
            for (heading in listOf(0.0, 90.0, 225.0)) {
                for (fov in listOf(36.87, 25.0, 32.2)) {
                    assertLandsAtPaddedCenter(MapPaddings(top = 300.0), tilt, heading, fov)
                    assertLandsAtPaddedCenter(MapPaddings(top = 120.0, left = 200.0, bottom = 40.0), tilt, heading, fov)
                }
            }
        }
    }

    @Test
    fun orthographicLandsAtPaddedCenter() {
        for (tilt in listOf(30.0, 60.0)) {
            assertLandsAtPaddedCenter(MapPaddings(top = 300.0, right = 100.0), tilt, 45.0, 0.0)
        }
    }

    @Test
    fun unknownFovFallsBackToFlat() {
        val flat =
            CameraPaddingEmulation.centerForTarget(target, zoom, 0.0, 0.0, MapPaddings(top = 300.0), null, height)
        val tilted =
            CameraPaddingEmulation.centerForTarget(target, zoom, 60.0, 0.0, MapPaddings(top = 300.0), null, height)
        assertEquals(flat.latitude, tilted.latitude, 0.0)
    }

    @Test
    fun fovIsKeptByCopyAndIgnoredByEquals() {
        val camera = MapCameraPosition(position = target, zoom = 10.0, fov = 36.87)
        assertEquals(36.87, camera.copy(zoom = 11.0).fov!!, 0.0)
        val withoutFov: MapCameraPositionInterface = MapCameraPosition(position = target, zoom = 10.0)
        assertEquals(true, camera.equals(withoutFov))
    }
}
