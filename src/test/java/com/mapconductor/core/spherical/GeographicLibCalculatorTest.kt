package com.mapconductor.core.spherical

import com.mapconductor.core.features.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 自前 Vincenty 実装（外部 geographiclib 依存の置き換え）の数値サニティテスト。
 * 既知の測地線距離・補間の性質を検証し、移植ミスを検出する。
 */
class GeographicLibCalculatorTest {
    private val tokyo = GeoPoint(35.6762, 139.6503)
    private val newYork = GeoPoint(40.7128, -74.0060)
    private val london = GeoPoint(51.5074, -0.1278)

    @Test
    fun distance_matchesKnownGeodesicValues() {
        // 東京–ニューヨーク: WGS84 測地線距離はおよそ 10,860 km
        val tokyoNy = GeographicLibCalculator.computeDistanceBetween(tokyo, newYork)
        assertEquals(10_860_000.0, tokyoNy, 20_000.0)

        // 東京–ロンドン: WGS84 測地線距離はおよそ 9,582 km（球面 haversine は約 9,559 km）
        val tokyoLondon = GeographicLibCalculator.computeDistanceBetween(tokyo, london)
        assertEquals(9_582_000.0, tokyoLondon, 20_000.0)

        // 赤道上の経度 1 度: およそ 111.32 km
        val oneDegree =
            GeographicLibCalculator.computeDistanceBetween(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertEquals(111_320.0, oneDegree, 100.0)
    }

    @Test
    fun distance_isSymmetricAndZeroForSamePoint() {
        val ab = GeographicLibCalculator.computeDistanceBetween(tokyo, newYork)
        val ba = GeographicLibCalculator.computeDistanceBetween(newYork, tokyo)
        assertEquals(ab, ba, 0.01)
        assertEquals(0.0, GeographicLibCalculator.computeDistanceBetween(tokyo, tokyo), 0.001)
    }

    @Test
    fun distance_agreesWithSphericalWithinEllipsoidTolerance() {
        // 楕円体（Vincenty）と球（haversine）の差は 0.5% 以内に収まるはず
        val vincenty = GeographicLibCalculator.computeDistanceBetween(tokyo, newYork)
        val haversine = Spherical.computeDistanceBetween(tokyo, newYork)
        assertTrue(
            "vincenty=$vincenty haversine=$haversine",
            abs(vincenty - haversine) / haversine < 0.005,
        )
    }

    @Test
    fun interpolate_endpointsAndMidpoint() {
        val start = GeographicLibCalculator.interpolate(tokyo, newYork, 0.0)
        assertEquals(tokyo.latitude, start.latitude, 1e-6)
        assertEquals(tokyo.longitude, start.longitude, 1e-6)

        val end = GeographicLibCalculator.interpolate(tokyo, newYork, 1.0)
        assertEquals(newYork.latitude, end.latitude, 1e-6)
        assertEquals(newYork.longitude, end.longitude, 1e-6)

        // 中点は両端から等距離（測地線上）
        val mid = GeographicLibCalculator.interpolate(tokyo, newYork, 0.5)
        val dFrom = GeographicLibCalculator.computeDistanceBetween(tokyo, mid)
        val dTo = GeographicLibCalculator.computeDistanceBetween(mid, newYork)
        assertEquals(dFrom, dTo, 1.0)
        val total = GeographicLibCalculator.computeDistanceBetween(tokyo, newYork)
        assertEquals(total, dFrom + dTo, 10.0)
    }

    @Test
    fun interpolate_nearAntipodal_doesNotHangAndReturnsFinite() {
        // 近対蹠点: Vincenty が収束しない場合は haversine フォールバックが働くこと
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(0.5, 179.7)
        val d = GeographicLibCalculator.computeDistanceBetween(a, b)
        assertTrue("distance=$d", d.isFinite() && d > 19_000_000.0 && d < 20_100_000.0)
        val mid = GeographicLibCalculator.interpolate(a, b, 0.5)
        assertTrue(mid.latitude.isFinite() && mid.longitude.isFinite())
    }

    @Test
    fun pointOnGeodesicSegment_findsClosestPoint() {
        val from = GeoPoint(35.0, 139.0)
        val to = GeoPoint(35.0, 141.0)
        // 線分中央付近の少し北の点
        val position = GeoPoint(35.1, 140.0)
        val result = pointOnGeodesicSegmentOrNull(from, to, position, thresholdMeters = 50_000.0)
        assertTrue(result != null)
        val (closest, distance) = result!!
        assertEquals(140.0, closest.longitude, 0.05)
        assertTrue("distance=$distance", distance in 1_000.0..20_000.0)
        // しきい値未満なら null
        val rejected = pointOnGeodesicSegmentOrNull(from, to, position, thresholdMeters = 1_000.0)
        assertTrue(rejected == null)
    }
}
