package com.mapconductor.core.spherical

import com.mapconductor.core.features.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class InterpolateAtMeridianLinearTest {
    @Test
    fun eastwardCrossing_interpolatesLatitudeAtMidpoint() {
        // 170°E → -170°(=190°E) の横断。180° はちょうど中間なので緯度も中間になる
        val result =
            interpolateAtMeridianLinear(
                from = GeoPoint(10.0, 170.0),
                to = GeoPoint(20.0, -170.0),
            )
        assertEquals(180.0, result.longitude, 1e-9)
        assertEquals(15.0, result.latitude, 1e-9)
    }

    @Test
    fun westwardCrossing_interpolatesLatitudeAtMidpoint() {
        val result =
            interpolateAtMeridianLinear(
                from = GeoPoint(10.0, -170.0),
                to = GeoPoint(20.0, 170.0),
            )
        assertEquals(-180.0, result.longitude, 1e-9)
        assertEquals(15.0, result.latitude, 1e-9)
    }

    @Test
    fun asymmetricCrossing_latitudeProportionalToShortWaySpan() {
        // 175°E → -165°(=195°E)、横断点 180° は区間の 1/4 地点
        val result =
            interpolateAtMeridianLinear(
                from = GeoPoint(0.0, 175.0),
                to = GeoPoint(40.0, -165.0),
            )
        assertEquals(180.0, result.longitude, 1e-9)
        assertEquals(10.0, result.latitude, 1e-9)
    }

    @Test
    fun splitByMeridian_linear_crossingLatitudeIsCorrect() {
        // splitByMeridian 経由でも正しい交点緯度が入ること
        val segments =
            splitByMeridian(
                listOf(GeoPoint(10.0, 170.0), GeoPoint(20.0, -170.0)),
                geodesic = false,
            )
        assertEquals(2, segments.size)
        val crossing = segments.first().last()
        assertEquals(180.0, crossing.longitude, 1e-9)
        assertEquals(15.0, crossing.latitude, 1e-9)
    }
}
