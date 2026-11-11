package com.mapconductor.core.marker

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- 地図の向き（heading）に合わせた並び ----

    private val north = GeoPoint(35.71, 139.8, 0.0)
    private val south = GeoPoint(35.69, 139.8, 0.0)
    private val east = GeoPoint(35.7, 139.81, 0.0)
    private val west = GeoPoint(35.7, 139.79, 0.0)

    private fun inFront(
        a: GeoPoint,
        b: GeoPoint,
        heading: Double,
        origin: Double = 139.8,
    ): Boolean = MarkerDrawOrder.of(null, a, heading, origin) > MarkerDrawOrder.of(null, b, heading, origin)

    @Test
    fun headingZeroKeepsNorthUpOrder() {
        val points = listOf(tokyo, sydney, north, south, east, west, GeoPoint(60.0, -170.0, 0.0), GeoPoint(-10.0, 20.0, 0.0))
        for (a in points) {
            for (b in points) {
                if (a == b) continue
                assertEquals(
                    "$a vs $b",
                    MarkerDrawOrder.of(null, a) > MarkerDrawOrder.of(null, b),
                    MarkerDrawOrder.of(null, a, 0.0, 0.0) > MarkerDrawOrder.of(null, b, 0.0, 0.0),
                )
            }
        }
    }

    @Test
    fun lowerOnScreenIsInFrontAtEveryHeading() {
        // 0°: 南が画面の下。
        assertTrue(inFront(south, north, 0.0))
        assertTrue(inFront(west, east, 0.0))
        // 90°（画面の上が東）: 西が画面の下。
        assertTrue(inFront(west, east, 90.0))
        // 同じ経度の南北は画面の同じ高さに並ぶ。左（北）が手前。
        assertTrue(inFront(north, south, 90.0))
        // 180°: 北が画面の下。
        assertTrue(inFront(north, south, 180.0))
        // 270°（画面の上が西）: 東が画面の下。
        assertTrue(inFront(east, west, 270.0))
        assertTrue(inFront(south, north, 270.0))
    }

    @Test
    fun zIndexDominatesAtEveryHeading() {
        for (heading in listOf(0.0, 45.0, 90.0, 135.0, 180.0, 270.0, 359.0)) {
            for (a in listOf(north, south, east, west, sydney)) {
                for (b in listOf(north, south, east, west, sydney)) {
                    assertTrue(MarkerDrawOrder.of(1, a, heading, 139.8) > MarkerDrawOrder.of(null, b, heading, 139.8))
                    assertTrue(MarkerDrawOrder.ofInt(1, a, heading, 139.8) > MarkerDrawOrder.ofInt(null, b, heading, 139.8))
                    val v = MarkerDrawOrder.of(0, a, heading, 139.8)
                    assertTrue(v >= 0.0 && v < 0.5)
                }
            }
        }
    }

    @Test
    fun antimeridianPairOrdersAcrossTheSeam() {
        // 日付変更線の両側。179.99E は 179.99W の西隣。
        val westOfSeam = GeoPoint(0.0, 179.99, 0.0)
        val eastOfSeam = GeoPoint(0.0, -179.99, 0.0)
        // 90°（画面の上が東）では西が手前。
        assertTrue(inFront(westOfSeam, eastOfSeam, 90.0, origin = 180.0))
        assertTrue(inFront(eastOfSeam, westOfSeam, 270.0, origin = 180.0))
    }

    @Test
    fun frameChangesOnlyOnBearingOrFarOrigin() {
        val f = MarkerDrawOrder.Frame(10.0, 139.0)
        assertFalse(MarkerDrawOrder.Frame(10.2, 150.0).differsEnoughFrom(f))
        assertTrue(MarkerDrawOrder.Frame(10.6, 139.0).differsEnoughFrom(f))
        assertTrue(MarkerDrawOrder.Frame(359.9, 139.0).differsEnoughFrom(MarkerDrawOrder.Frame(0.5, 139.0)))
        assertFalse(MarkerDrawOrder.Frame(359.9, 139.0).differsEnoughFrom(MarkerDrawOrder.Frame(0.1, 139.0)))
        assertTrue(MarkerDrawOrder.Frame(10.0, -100.0).differsEnoughFrom(f))
    }
}
