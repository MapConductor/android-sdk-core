package com.mapconductor.core.geometry

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.normalize
import com.mapconductor.core.polygon.PolygonEntity
import com.mapconductor.core.polygon.PolygonManager
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.spherical.Planar
import com.mapconductor.core.spherical.WGS84Geodesic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.tan

/**
 * geodesic ポリゴンの閉じる辺（末尾 → 先頭）も大圏で補間すること。
 *
 * js-sdk-core の `GeodesicPolygonRing.test.mjs`、ios-sdk の `GeodesicPolygonRingTests` と 1 対 1 で対応する。
 *
 * [WGS84Geodesic.createInterpolatePoints] は隣り合う点の間しか補間しない。ポリゴンの点は開いたリング
 * （末尾 ≠ 先頭）で渡されるので、塗りのリングは閉じる辺だけが補間されず直線の弦になっていた。
 * 輪郭はリングを閉じてからポリラインとして密度化するので大圏になり、「輪郭は曲線、塗りの一辺だけ直線」になる。
 * Google のように塗りと輪郭を 1 つのネイティブポリゴンで描くプロバイダは、輪郭まで直線になる。
 */
class GeodesicPolygonRingTest {
    private val boston = GeoPoint.fromLatLong(42.36, -71.06)
    private val london = GeoPoint.fromLatLong(51.5, -0.12)
    private val iceland = GeoPoint.fromLatLong(64.1, -21.9)

    // 実機検証と同じ順序。閉じる辺は アイスランド → ボストン。
    private val triangle = listOf(boston, london, iceland)

    private fun same(
        a: GeoPointInterface,
        b: GeoPointInterface,
    ): Boolean = abs(a.latitude - b.latitude) < 1e-9 && abs(a.longitude - b.longitude) < 1e-9

    private fun assertSamePoints(
        expected: List<GeoPointInterface>,
        actual: List<GeoPointInterface>,
    ) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { i -> assertTrue("point $i: ${actual[i]} != ${expected[i]}", same(expected[i], actual[i])) }
    }

    /** 輪郭（閉じたリングをポリラインとして密度化）から、末尾の重複点を除いたもの。 */
    private fun outlineOf(
        points: List<GeoPointInterface>,
        geodesic: Boolean,
    ): List<GeoPointInterface> = buildUnwrappedPolylinePath(points + points.first(), geodesic).dropLast(1)

    @Test
    fun greatCircleMidpoint_bostonToIceland_bulgesNorthwestByAboutFourDegrees() {
        val mid = WGS84Geodesic.interpolate(boston, iceland, 0.5)

        // Web メルカトルでの直線（弦）が同じ経度で通る緯度。
        fun merc(lat: Double) = ln(tan(PI / 4 + lat * PI / 360))
        val t = (mid.longitude - boston.longitude) / (iceland.longitude - boston.longitude)
        val y = merc(boston.latitude) + t * (merc(iceland.latitude) - merc(boston.latitude))
        val chordLat = (2 * atan(exp(y)) - PI / 2) * 180 / PI
        assertTrue("mid ${mid.latitude}", abs(mid.latitude - 55.63) < 0.05)
        assertTrue("chord $chordLat", abs(chordLat - 51.6) < 0.05)
    }

    @Test
    fun densifyGeodesicRing_interpolatesClosingEdge_andKeepsRingOpen() {
        val ring = densifyGeodesicRing(triangle)
        assertTrue(same(boston, ring.first()))
        // 先頭の重複点を末尾に付けない（閉じて補間した結果から末尾の 1 点だけを除いた長さ）。
        // createInterpolatePoints は分割比を足し込むため、末尾の手前に終点とほぼ同じ点が残ることがある。
        assertEquals(WGS84Geodesic.createInterpolatePoints(triangle + boston).size - 1, ring.size)
        assertNotSame(boston, ring.last())
        val expected = WGS84Geodesic.createInterpolatePoints(listOf(iceland, boston))
        assertSamePoints(expected.subList(1, expected.size - 1), ring.takeLast(expected.size - 2))
    }

    @Test
    fun densifyGeodesicRing_keepsClosedRingClosed() {
        val closed = triangle + boston
        assertSamePoints(WGS84Geodesic.createInterpolatePoints(closed), densifyGeodesicRing(closed))
    }

    @Test
    fun unwrappedGeodesicFill_matchesOutline() {
        val fill = buildUnwrappedPolygonRings(triangle, emptyList(), geodesic = true).outerRings.single()
        assertSamePoints(outlineOf(triangle, true), fill)
        val mid = WGS84Geodesic.interpolate(iceland, boston, 0.5)
        val nearest = fill.minOf { hypot(it.latitude - mid.latitude, it.longitude - mid.longitude) }
        assertTrue("nearest $nearest", nearest < 0.1)
    }

    @Test
    fun splitGeodesicFill_interpolatesClosingEdge() {
        val rings = buildPolygonRings(triangle, emptyList(), geodesic = true).outerRings
        assertEquals(1, rings.size)
        assertSamePoints(outlineOf(triangle, true), rings.single())
    }

    @Test
    fun nonGeodesicFill_isUnchanged() {
        val legacy = Planar.createInterpolatePoints(triangle).map { it.normalize() }
        val fill = buildUnwrappedPolygonRings(triangle, emptyList(), geodesic = false).outerRings.single()
        assertSamePoints(legacy, fill)
        assertTrue(same(iceland, fill.last()))
        assertSamePoints(legacy, buildPolygonRings(triangle, emptyList(), geodesic = false).outerRings.single())
    }

    @Test
    fun geodesicHoles_interpolateClosingEdge() {
        val outer =
            listOf(
                GeoPoint.fromLatLong(30.0, -80.0),
                GeoPoint.fromLatLong(30.0, 10.0),
                GeoPoint.fromLatLong(70.0, 10.0),
                GeoPoint.fromLatLong(70.0, -80.0),
            )
        val hole = triangle
        val unwrapped = buildUnwrappedPolygonRings(outer, listOf(hole), geodesic = true)
        assertSamePoints(outlineOf(hole, true), unwrapped.holeRings.single())
        assertSamePoints(outlineOf(outer, true), unwrapped.outerRings.single())
        assertSamePoints(outlineOf(hole, true), buildPolygonRings(outer, listOf(hole), geodesic = true).holeRings.single())
        assertSamePoints(
            Planar.createInterpolatePoints(hole).map { it.normalize() },
            buildUnwrappedPolygonRings(outer, listOf(hole), geodesic = false).holeRings.single(),
        )
    }

    @Test
    fun hitTest_followsGreatCircleOnClosingEdge() {
        // 弦と大圏の間（北西へ膨らんだ部分）の点。
        val mid = WGS84Geodesic.interpolate(iceland, boston, 0.5)
        val t = (mid.longitude - iceland.longitude) / (boston.longitude - iceland.longitude)
        val chordLat = iceland.latitude + t * (boston.latitude - iceland.latitude)
        val bulge = GeoPoint.fromLatLong((mid.latitude + chordLat) / 2, mid.longitude)

        val manager = PolygonManager<Unit>()
        val state = PolygonState(points = triangle, id = "g", geodesic = true)
        manager.registerEntity(PolygonEntity(polygon = Unit, state = state))
        assertEquals("g", manager.find(bulge)?.state?.id)

        state.geodesic = false
        assertNull(manager.find(bulge))
    }
}
