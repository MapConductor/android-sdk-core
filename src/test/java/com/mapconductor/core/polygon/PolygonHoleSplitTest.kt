package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [splitPolygonWithHolesIntoSimpleRings]（分割方式）の性質テスト。
 * ios-sdk `PolygonHoleSplitTests.swift` と同じ観点で、
 * 出力リング群の偶奇塗り合成が「外周の内側かつ全穴の外側」と一致し、
 * 各リングが CCW・単純であることを確認する。
 */
class PolygonHoleSplitTest {
    private fun rect(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): List<GeoPointInterface> =
        listOf(
            GeoPoint(latitude = south, longitude = west),
            GeoPoint(latitude = south, longitude = east),
            GeoPoint(latitude = north, longitude = east),
            GeoPoint(latitude = north, longitude = west),
        )

    private fun evenOddInside(
        lat: Double,
        lng: Double,
        ring: List<GeoPointInterface>,
    ): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.latitude > lat) != (b.latitude > lat)) {
                val x =
                    a.longitude +
                        ((lat - a.latitude) / (b.latitude - a.latitude)) * (b.longitude - a.longitude)
                if (lng < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun filledByAny(
        lat: Double,
        lng: Double,
        rings: List<List<GeoPointInterface>>,
    ): Boolean = rings.any { evenOddInside(lat, lng, it) }

    private fun signedAreaLonLat(ring: List<GeoPointInterface>): Double {
        var area = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            area += (a.longitude * b.latitude) - (b.longitude * a.latitude)
        }
        return area / 2
    }

    private fun maxAbsLngStep(ring: List<GeoPointInterface>): Double =
        ring.indices.maxOfOrNull { i ->
            abs(ring[(i + 1) % ring.size].longitude - ring[i].longitude)
        } ?: 0.0

    @Test
    fun noHolesReturnsSingleRing() {
        val rings = splitPolygonWithHolesIntoSimpleRings(rect(0.0, 0.0, 10.0, 10.0), emptyList())
        assertEquals(1, rings.size)
        assertTrue(filledByAny(5.0, 5.0, rings))
    }

    @Test
    fun singleHoleSplitsIntoTwoSimpleRings() {
        val rings =
            splitPolygonWithHolesIntoSimpleRings(
                rect(0.0, 0.0, 10.0, 10.0),
                listOf(rect(4.0, 4.0, 6.0, 6.0)),
            )
        assertEquals(2, rings.size)
        // 全リング CCW
        rings.forEach { assertTrue(signedAreaLonLat(it) > 0) }
        // 面積の合計 = 100 - 4
        assertEquals(96.0, rings.sumOf { signedAreaLonLat(it) }, 1e-9)
        // 穴は抜け、外周内は塗られる
        assertFalse(filledByAny(5.0, 5.0, rings))
        assertTrue(filledByAny(2.0, 2.0, rings))
        assertTrue(filledByAny(8.0, 8.0, rings))
        assertTrue(filledByAny(5.0, 8.0, rings))
        assertTrue(filledByAny(5.0, 2.0, rings))
        assertFalse(filledByAny(11.0, 5.0, rings))
    }

    @Test
    fun triangleHole() {
        val hole =
            listOf(
                GeoPoint(latitude = 6.0, longitude = 5.0),
                GeoPoint(latitude = 4.0, longitude = 6.0),
                GeoPoint(latitude = 4.0, longitude = 4.0),
            )
        val rings = splitPolygonWithHolesIntoSimpleRings(rect(0.0, 0.0, 10.0, 10.0), listOf(hole))
        assertEquals(2, rings.size)
        assertEquals(100.0 - 2.0, rings.sumOf { signedAreaLonLat(it) }, 1e-9)
        assertFalse(filledByAny(4.7, 5.0, rings))
        assertTrue(filledByAny(2.0, 2.0, rings))
        assertTrue(filledByAny(8.0, 8.0, rings))
    }

    @Test
    fun twoDisjointHoles() {
        val rings =
            splitPolygonWithHolesIntoSimpleRings(
                rect(0.0, 0.0, 10.0, 10.0),
                listOf(rect(1.0, 1.0, 3.0, 3.0), rect(6.0, 6.0, 8.0, 8.0)),
            )
        assertEquals(3, rings.size)
        assertEquals(100.0 - 8.0, rings.sumOf { signedAreaLonLat(it) }, 1e-9)
        assertFalse(filledByAny(2.0, 2.0, rings))
        assertFalse(filledByAny(7.0, 7.0, rings))
        assertTrue(filledByAny(5.0, 5.0, rings))
        assertTrue(filledByAny(9.0, 1.0, rings))
    }

    private fun worldMaskOuter(): List<GeoPointInterface> =
        listOf(
            GeoPoint(latitude = 85.0, longitude = 90.0),
            GeoPoint(latitude = 85.0, longitude = 0.1),
            GeoPoint(latitude = 85.0, longitude = -90.0),
            GeoPoint(latitude = 85.0, longitude = -179.9),
            GeoPoint(latitude = 0.0, longitude = -179.9),
            GeoPoint(latitude = -85.0, longitude = -179.9),
            GeoPoint(latitude = -85.0, longitude = -90.0),
            GeoPoint(latitude = -85.0, longitude = 0.1),
            GeoPoint(latitude = -85.0, longitude = 90.0),
            GeoPoint(latitude = -85.0, longitude = 179.9),
            GeoPoint(latitude = 0.0, longitude = 179.9),
            GeoPoint(latitude = 85.0, longitude = 179.9),
        )

    /**
     * サンプルページ相当: 世界マスク外周 + 札幌近郊の三角形の穴。
     * 東側の外周（経度 179.9）へ橋が張られ、全エッジの経度ステップが 180° 以下になる。
     */
    @Test
    fun worldMaskWithSapporoHole() {
        val hole =
            listOf(
                GeoPoint(latitude = 43.10086924222251, longitude = 141.35290903949243),
                GeoPoint(latitude = 43.04444342582366, longitude = 141.4118953480885),
                GeoPoint(latitude = 43.05060149394299, longitude = 141.30656265416695),
            )
        val rings = splitPolygonWithHolesIntoSimpleRings(worldMaskOuter(), listOf(hole))
        assertEquals(2, rings.size)
        rings.forEach {
            assertTrue(signedAreaLonLat(it) > 0)
            assertTrue(maxAbsLngStep(it) <= 180.0)
        }
        // 穴の重心は抜ける
        assertFalse(filledByAny(43.0653, 141.3571, rings))
        // 穴の外は塗られる
        assertTrue(filledByAny(35.0, 139.0, rings))
        assertTrue(filledByAny(-30.0, 20.0, rings))
        assertTrue(filledByAny(43.07, 150.0, rings))
        assertTrue(filledByAny(43.07, 100.0, rings))
    }

    /** 世界マスク外周 + 離れた 2 つの三角形の穴（サンプルの自動ドリフト後に相当）。 */
    @Test
    fun worldMaskWithTwoSapporoHoles() {
        val drift = 0.35
        val hole1 =
            listOf(
                GeoPoint(latitude = 43.10086924222251, longitude = 141.35290903949243 + drift),
                GeoPoint(latitude = 43.04444342582366, longitude = 141.4118953480885 + drift),
                GeoPoint(latitude = 43.05060149394299, longitude = 141.30656265416695 + drift),
            )
        val hole2 =
            listOf(
                GeoPoint(latitude = 43.06035050410283, longitude = 141.31990479539704),
                GeoPoint(latitude = 43.038284739487004, longitude = 141.33324693662706),
                GeoPoint(latitude = 43.049062034871525, longitude = 141.28690055130158),
            )
        val rings = splitPolygonWithHolesIntoSimpleRings(worldMaskOuter(), listOf(hole1, hole2))
        assertEquals(3, rings.size)
        rings.forEach {
            assertTrue(signedAreaLonLat(it) > 0)
            assertTrue(maxAbsLngStep(it) <= 180.0)
        }
        // 両方の穴の重心は抜ける
        assertFalse(filledByAny(43.0653, 141.3571 + drift, rings))
        assertFalse(filledByAny(43.0493, 141.3133, rings))
        // 穴から離れた広域は塗られる
        assertTrue(filledByAny(44.0, 140.0, rings))
        assertTrue(filledByAny(42.0, 140.0, rings))
        assertTrue(filledByAny(46.0, 143.0, rings))
        assertTrue(filledByAny(40.0, 143.0, rings))
        assertTrue(filledByAny(43.06, 141.5, rings))
        assertTrue(filledByAny(0.0, 0.0, rings))
        assertTrue(filledByAny(-45.0, -90.0, rings))
    }
}
