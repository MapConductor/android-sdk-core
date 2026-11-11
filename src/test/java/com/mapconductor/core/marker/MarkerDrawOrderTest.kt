package com.mapconductor.core.marker

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPoint
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(InternalMapConductorApi::class)
class MarkerDrawOrderTest {
    private val tokyo = GeoPoint(35.7, 139.8, 0.0)
    private val sydney = GeoPoint(-33.9, 151.2, 0.0)

    @Test
    fun explicitZIndexBeatsUnspecifiedInBothHemispheres() {
        for (point in listOf(tokyo, sydney, GeoPoint(-89.9, -179.9, 0.0), GeoPoint(89.9, 179.9, 0.0))) {
            assertTrue(MarkerDrawOrder.of(1, point) > MarkerDrawOrder.of(null, point))
            assertTrue(MarkerDrawOrder.ofInt(1, point) > MarkerDrawOrder.ofInt(null, point))
        }
        // 南半球の未指定でも、北の明示 1 を越えない。
        assertTrue(MarkerDrawOrder.of(1, tokyo) > MarkerDrawOrder.of(null, GeoPoint(-89.9, -179.9, 0.0)))
        assertTrue(MarkerDrawOrder.ofInt(1, tokyo) > MarkerDrawOrder.ofInt(null, GeoPoint(-89.9, -179.9, 0.0)))
    }

    @Test
    fun zIndexIsPrimaryIncludingNegatives() {
        assertTrue(MarkerDrawOrder.of(-1, sydney) < MarkerDrawOrder.of(0, tokyo))
        assertTrue(MarkerDrawOrder.of(2, tokyo) > MarkerDrawOrder.of(1, sydney))
        assertTrue(MarkerDrawOrder.ofInt(-1, sydney) < MarkerDrawOrder.ofInt(0, tokyo))
    }

    @Test
    fun southThenWestBreakTies() {
        val north = GeoPoint(35.7001, 139.8, 0.0)
        val west = GeoPoint(35.7, 139.7999, 0.0)
        assertTrue(MarkerDrawOrder.of(null, tokyo) > MarkerDrawOrder.of(null, north))
        assertTrue(MarkerDrawOrder.of(null, west) > MarkerDrawOrder.of(null, tokyo))
        assertTrue(MarkerDrawOrder.of(3, sydney) > MarkerDrawOrder.of(3, tokyo))
        // Float にしても数メートルの緯度差は残る（Google Maps は Float で受ける）。
        val tenMetersSouth = GeoPoint(35.7 - 0.0001, 139.8, 0.0)
        assertTrue(MarkerDrawOrder.of(null, tenMetersSouth).toFloat() > MarkerDrawOrder.of(null, tokyo).toFloat())
        assertTrue(MarkerDrawOrder.ofInt(null, sydney) > MarkerDrawOrder.ofInt(null, tokyo))
    }

    @Test
    fun tiebreakStaysBelowHalf() {
        val extreme = GeoPoint(-90.0, -180.0, 0.0)
        assertTrue(MarkerDrawOrder.of(0, extreme) < 0.5)
        assertTrue(MarkerDrawOrder.of(0, GeoPoint(90.0, 180.0, 0.0)) >= 0.0)
    }
}
