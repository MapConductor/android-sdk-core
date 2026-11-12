package com.mapconductor.core.projection

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.spherical.Spherical
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 透視（fov あり）の WebMercatorScreenProjection を、独立に組んだ 3D のカメラ
 * （MapLibre GL JS と同じ模型）と突き合わせる。
 */
class WebMercatorScreenProjectionTiltTest {
    private val width = 866f
    private val height = 1370f
    private val fov = 36.87
    private val center = GeoPoint.fromLatLong(35.6812, 139.7671)

    /** 地面の世界ピクセル座標 (dx 東, dy 南) → 画面。カメラを 3D に置いて光線を投げる。 */
    private fun reference(
        dxEast: Double,
        dySouth: Double,
        tiltDeg: Double,
        headingDeg: Double,
    ): Offset {
        // 世界を -heading 回して画面の上をカメラの向きに合わせる。
        val a = -headingDeg * Math.PI / 180.0
        val gx = dxEast * cos(a) - dySouth * sin(a)
        val gy = dxEast * sin(a) + dySouth * cos(a)
        val t = tiltDeg * Math.PI / 180.0
        val d = height / 2.0 / tan(fov * Math.PI / 360.0)
        // カメラは中央から画面の下側へ d·sin t、上へ d·cos t。
        val vx = gx
        val vy = gy - d * sin(t)
        val vz = -d * cos(t)
        val depth = -vy * sin(t) - vz * cos(t)
        val up = -vy * cos(t) + vz * sin(t)
        return Offset((width / 2.0 + d * vx / depth).toFloat(), (height / 2.0 - d * up / depth).toFloat())
    }

    private fun worldOffset(
        point: GeoPoint,
        zoom: Double,
    ): Pair<Double, Double> {
        val world = 256.0 * 2.0.pow(zoom)
        val extent = 2.0 * WEB_MERCATOR_MAX_EXTENT_METERS
        val c = WebMercator.project(center)
        val p = WebMercator.project(point)
        return Pair((p.x - c.x) / extent * world, -(p.y - c.y) / extent * world)
    }

    @Test
    fun matchesAnIndependentCameraAtEveryTiltAndBearing() {
        val points =
            listOf(
                GeoPoint.fromLatLong(35.6852, 139.7671),
                GeoPoint.fromLatLong(35.6772, 139.7711),
                GeoPoint.fromLatLong(35.6812, 139.7611),
                GeoPoint.fromLatLong(35.6840, 139.7700),
            )
        for (tilt in listOf(0.0, 30.0, 60.0)) {
            for (bearing in listOf(0.0, 90.0, -90.0, 30.0)) {
                val camera = MapCameraPosition(position = center, zoom = 15.0, bearing = bearing, tilt = tilt)
                for (point in points) {
                    val (dx, dy) = worldOffset(point, 15.0)
                    val heading = -bearing
                    val expected = reference(dx, dy, tilt, heading)
                    val actual = WebMercatorScreenProjection.toScreenOffset(point, camera, width, height, fov)
                    assertNotNull(actual)
                    assertEquals("x tilt=$tilt bearing=$bearing", expected.x, actual!!.x, 0.05f)
                    assertEquals("y tilt=$tilt bearing=$bearing", expected.y, actual.y, 0.05f)
                }
            }
        }
    }

    @Test
    fun roundTripsWithinCentimetres() {
        for (tilt in listOf(0.0, 30.0, 60.0)) {
            for (bearing in listOf(0.0, 90.0, -90.0)) {
                val camera = MapCameraPosition(position = center, zoom = 16.0, bearing = bearing, tilt = tilt)
                for ((x, y) in listOf(100f to 100f, 433f to 685f, 800f to 1300f, 433f to 20f)) {
                    val geo = WebMercatorScreenProjection.fromScreenOffset(Offset(x, y), camera, width, height, fov)
                    assertNotNull(geo)
                    val back = WebMercatorScreenProjection.toScreenOffset(geo!!, camera, width, height, fov)!!
                    assertEquals(x, back.x, 0.01f)
                    assertEquals(y, back.y, 0.01f)
                }
            }
        }
    }

    @Test
    fun withoutFovOrTiltItIsTheFlatProjection() {
        val point = GeoPoint.fromLatLong(35.6852, 139.7700)
        val flat = MapCameraPosition(position = center, zoom = 15.0, bearing = 30.0, tilt = 0.0)
        assertEquals(
            WebMercatorScreenProjection.toScreenOffset(point, flat, width, height),
            WebMercatorScreenProjection.toScreenOffset(point, flat, width, height, fov),
        )
        val tilted = flat.copy(tilt = 45.0)
        assertEquals(
            WebMercatorScreenProjection.toScreenOffset(point, tilted, width, height),
            WebMercatorScreenProjection.toScreenOffset(point, tilted, width, height, null),
        )
    }

    @Test
    fun aRayAboveTheHorizonHasNoGroundPoint() {
        // tilt 85 で画面最上部の光線は 85 + 18.4 > 90 度。
        val camera = MapCameraPosition(position = center, zoom = 15.0, tilt = 85.0)
        assertNull(WebMercatorScreenProjection.fromScreenOffset(Offset(433f, 0f), camera, width, height, fov))
        // 中央は必ず解ける。
        val mid = WebMercatorScreenProjection.fromScreenOffset(Offset(433f, 685f), camera, width, height, fov)
        assertEquals(0.0, Spherical.computeDistanceBetween(mid!!, center), 0.01)
    }
}
