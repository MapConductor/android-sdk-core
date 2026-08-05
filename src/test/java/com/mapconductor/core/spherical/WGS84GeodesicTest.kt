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
class WGS84GeodesicTest {
    private val tokyo = GeoPoint(35.6762, 139.6503)
    private val newYork = GeoPoint(40.7128, -74.0060)
    private val london = GeoPoint(51.5074, -0.1278)

    @Test
    fun distance_matchesKnownGeodesicValues() {
        // 東京–ニューヨーク: WGS84 測地線距離はおよそ 10,860 km
        val tokyoNy = WGS84Geodesic.computeDistanceBetween(tokyo, newYork)
        assertEquals(10_860_000.0, tokyoNy, 20_000.0)

        // 東京–ロンドン: WGS84 測地線距離はおよそ 9,582 km（球面 haversine は約 9,559 km）
        val tokyoLondon = WGS84Geodesic.computeDistanceBetween(tokyo, london)
        assertEquals(9_582_000.0, tokyoLondon, 20_000.0)

        // 赤道上の経度 1 度: およそ 111.32 km
        val oneDegree =
            WGS84Geodesic.computeDistanceBetween(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertEquals(111_320.0, oneDegree, 100.0)
    }

    @Test
    fun distance_isSymmetricAndZeroForSamePoint() {
        val ab = WGS84Geodesic.computeDistanceBetween(tokyo, newYork)
        val ba = WGS84Geodesic.computeDistanceBetween(newYork, tokyo)
        assertEquals(ab, ba, 0.01)
        assertEquals(0.0, WGS84Geodesic.computeDistanceBetween(tokyo, tokyo), 0.001)
    }

    @Test
    fun distance_agreesWithSphericalWithinEllipsoidTolerance() {
        // 楕円体（Vincenty）と球（haversine）の差は 0.5% 以内に収まるはず
        val vincenty = WGS84Geodesic.computeDistanceBetween(tokyo, newYork)
        val haversine = Spherical.computeDistanceBetween(tokyo, newYork)
        assertTrue(
            "vincenty=$vincenty haversine=$haversine",
            abs(vincenty - haversine) / haversine < 0.005,
        )
    }

    @Test
    fun interpolate_endpointsAndMidpoint() {
        val start = WGS84Geodesic.interpolate(tokyo, newYork, 0.0)
        assertEquals(tokyo.latitude, start.latitude, 1e-6)
        assertEquals(tokyo.longitude, start.longitude, 1e-6)

        val end = WGS84Geodesic.interpolate(tokyo, newYork, 1.0)
        assertEquals(newYork.latitude, end.latitude, 1e-6)
        assertEquals(newYork.longitude, end.longitude, 1e-6)

        // 中点は両端から等距離（測地線上）
        val mid = WGS84Geodesic.interpolate(tokyo, newYork, 0.5)
        val dFrom = WGS84Geodesic.computeDistanceBetween(tokyo, mid)
        val dTo = WGS84Geodesic.computeDistanceBetween(mid, newYork)
        assertEquals(dFrom, dTo, 1.0)
        val total = WGS84Geodesic.computeDistanceBetween(tokyo, newYork)
        assertEquals(total, dFrom + dTo, 10.0)
    }

    @Test
    fun interpolate_nearAntipodal_doesNotHangAndReturnsFinite() {
        // 近対蹠点: Vincenty が収束しない場合は haversine フォールバックが働くこと
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(0.5, 179.7)
        val d = WGS84Geodesic.computeDistanceBetween(a, b)
        assertTrue("distance=$d", d.isFinite() && d > 19_000_000.0 && d < 20_100_000.0)
        val mid = WGS84Geodesic.interpolate(a, b, 0.5)
        assertTrue(mid.latitude.isFinite() && mid.longitude.isFinite())
    }

    @Test
    fun pointOnGeodesicSegment_findsClosestPoint() {
        val from = GeoPoint(35.0, 139.0)
        val to = GeoPoint(35.0, 141.0)
        // 線分中央付近の少し北の点
        val position = GeoPoint(35.1, 140.0)
        val result = WGS84Geodesic.pointOnLineOrNull(from, to, position, thresholdMeters = 50_000.0)
        assertTrue(result != null)
        val (closest, distance) = result!!
        assertEquals(140.0, closest.longitude, 0.05)
        assertTrue("distance=$distance", distance in 1_000.0..20_000.0)
        // しきい値未満なら null
        val rejected = WGS84Geodesic.pointOnLineOrNull(from, to, position, thresholdMeters = 1_000.0)
        assertTrue(rejected == null)
    }

    // ── Spherical パリティ用に追加したメソッド ──────────────────────────────

    @Test
    fun computeHeading_matchesGeodesicInitialBearing() {
        // 東京→ニューヨークの初期方位はおよそ北北東（約 25°）
        val heading = WGS84Geodesic.computeHeading(tokyo, newYork)
        assertEquals(25.0, heading, 3.0)
    }

    @Test
    fun computeOffset_matchesDistanceAndHeading() {
        // computeOffset で進んだ先は、距離・初期方位と厳密に整合する
        val dest = WGS84Geodesic.computeOffset(tokyo, 100_000.0, 90.0)
        assertEquals(100_000.0, WGS84Geodesic.computeDistanceBetween(tokyo, dest), 1.0)
        assertEquals(90.0, WGS84Geodesic.computeHeading(tokyo, dest), 1e-3)
    }

    @Test
    fun computeOffsetOrigin_approximatelyInvertsOffset() {
        // Spherical と同じ逆方位(H+180)近似。厳密な逆ではないが元付近に戻る。
        // 残差は子午線収束に比例するため、短いレグ(10km)では ~10m 程度。
        val dest = WGS84Geodesic.computeOffset(tokyo, 10_000.0, 90.0)
        val origin = WGS84Geodesic.computeOffsetOrigin(dest, 10_000.0, 90.0)
        assertTrue(origin != null)
        assertTrue(WGS84Geodesic.computeDistanceBetween(tokyo, origin!!) < 100.0)
    }

    @Test
    fun computeLength_sumsSegments() {
        val path = listOf(tokyo, newYork, london)
        val expected =
            WGS84Geodesic.computeDistanceBetween(tokyo, newYork) +
                WGS84Geodesic.computeDistanceBetween(newYork, london)
        assertEquals(expected, WGS84Geodesic.computeLength(path), 1.0)
    }

    @Test
    fun computeArea_ellipsoidalCellMatchesReference() {
        // 赤道上の 1°×1° セル。GeographicLib の測地線面積とほぼ一致（約 1.23085e10 m²）
        val cell = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(1.0, 0.0),
            GeoPoint(1.0, 1.0),
            GeoPoint(0.0, 1.0),
        )
        assertEquals(1.230846e10, WGS84Geodesic.computeArea(cell), 1e7)

        // 逆回りで符号が反転し、絶対値は computeArea と一致
        val signed = WGS84Geodesic.computeSignedArea(cell)
        val reversed = WGS84Geodesic.computeSignedArea(cell.reversed())
        assertEquals(-signed, reversed, 1.0)
        assertEquals(abs(signed), WGS84Geodesic.computeArea(cell), 1e-3)

        // 楕円体面積は球面（赤道半径）版よりわずかに小さい
        assertTrue(WGS84Geodesic.computeArea(cell) < Spherical.computeArea(cell))

        // 3 点未満は 0
        assertEquals(0.0, WGS84Geodesic.computeArea(listOf(tokyo, newYork)), 0.0)
    }
}
