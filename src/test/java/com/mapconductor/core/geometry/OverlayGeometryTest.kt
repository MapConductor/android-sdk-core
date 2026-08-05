package com.mapconductor.core.geometry

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.normalize
import com.mapconductor.core.spherical.WGS84Geodesic
import com.mapconductor.core.spherical.Planar
import com.mapconductor.core.spherical.splitByMeridian
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共通ジオメトリパイプラインが、各ドライバーにインライン実装されていた
 * 従来アルゴリズム（densify → normalize → splitByMeridian → フィルタ）と
 * 同一の結果を返すことを確認するテスト。
 */
class OverlayGeometryTest {
    private val tokyo = GeoPoint(35.68, 139.76)
    private val newYork = GeoPoint(40.71, -74.0)
    private val sydney = GeoPoint(-33.86, 151.2)
    private val honolulu = GeoPoint(21.3, -157.85)

    private fun legacyInterpolate(
        points: List<GeoPointInterface>,
        geodesic: Boolean,
    ): List<GeoPointInterface> =
        when (geodesic) {
            true -> WGS84Geodesic.createInterpolatePoints(points)
            false -> Planar.createInterpolatePoints(points)
        }.map { it.normalize() }

    @Test
    fun polylineSegments_matchLegacyAlgorithm_geodesic() {
        val points = listOf(tokyo, newYork)
        val expected =
            splitByMeridian(legacyInterpolate(points, true), true).filter { it.size >= 2 }
        assertEquals(expected, buildPolylineSegments(points, geodesic = true))
        // 東京→NY の測地線は太平洋（±180°）を跨ぐため複数セグメントに分割される
        assertTrue(expected.size >= 2)
    }

    @Test
    fun polylineSegments_matchLegacyAlgorithm_linear() {
        val points = listOf(tokyo, sydney, honolulu)
        val expected =
            splitByMeridian(legacyInterpolate(points, false), false).filter { it.size >= 2 }
        assertEquals(expected, buildPolylineSegments(points, geodesic = false))
    }

    @Test
    fun polylineSegments_degenerateInput_returnsEmpty() {
        assertTrue(buildPolylineSegments(emptyList(), geodesic = true).isEmpty())
        assertTrue(buildPolylineSegments(listOf(tokyo), geodesic = true).isEmpty())
    }

    @Test
    fun polygonRings_singleRing_includesHoles() {
        val outer =
            listOf(
                GeoPoint(35.0, 139.0),
                GeoPoint(35.0, 140.0),
                GeoPoint(36.0, 140.0),
                GeoPoint(36.0, 139.0),
            )
        val hole =
            listOf(
                GeoPoint(35.4, 139.4),
                GeoPoint(35.4, 139.6),
                GeoPoint(35.6, 139.6),
                GeoPoint(35.6, 139.4),
            )
        val rings = buildPolygonRings(outer, listOf(hole), geodesic = true)
        assertEquals(1, rings.outerRings.size)
        assertEquals(1, rings.holeRings.size)
        assertEquals(legacyInterpolate(hole, true), rings.holeRings.first())
    }

    @Test
    fun polygonRings_meridianSplitOuter_dropsHoles() {
        // ±180° を跨ぐ外周（従来仕様: 分割時は穴を含めない）
        val outer =
            listOf(
                GeoPoint(10.0, 170.0),
                GeoPoint(10.0, -170.0),
                GeoPoint(20.0, -170.0),
                GeoPoint(20.0, 170.0),
            )
        val hole =
            listOf(
                GeoPoint(14.0, 178.0),
                GeoPoint(14.0, 179.0),
                GeoPoint(15.0, 179.0),
            )
        val rings = buildPolygonRings(outer, listOf(hole), geodesic = false)
        assertTrue(rings.outerRings.size >= 2)
        assertTrue(rings.holeRings.isEmpty())
    }

    @Test
    fun polygonRings_degenerateInput_returnsEmpty() {
        val rings = buildPolygonRings(listOf(tokyo, newYork), emptyList(), geodesic = true)
        assertTrue(rings.outerRings.isEmpty())
        assertTrue(rings.holeRings.isEmpty())
    }

    @Test
    fun closeRing_appendsFirstPointOnlyWhenOpen() {
        val open = listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c", "a"), closeRing(open))
        assertEquals(listOf("a", "b", "a"), closeRing(listOf("a", "b", "a")))
        assertTrue(closeRing(emptyList<String>()).isEmpty())
    }

    // ---- unwrap 版パイプライン（GL 系向け） ----

    @Test
    fun unwrappedPolyline_antimeridianCrossing_isSingleContinuousPath() {
        val path = buildUnwrappedPolylinePath(listOf(tokyo, newYork), geodesic = true)
        assertTrue(path.size >= 2)
        // 分割されず 1 本の連続パス（経度が ±360 ジャンプしない）
        for (i in 0 until path.size - 1) {
            val diff = kotlin.math.abs(path[i + 1].longitude - path[i].longitude)
            assertTrue("jump=$diff", diff < 180.0)
        }
        // 頂点数は分割版の合計から交差挿入点（2 点/交差）を除いたものと一致する
        val split = buildPolylineSegments(listOf(tokyo, newYork), geodesic = true)
        val crossings = split.size - 1
        assertEquals(split.sumOf { it.size } - crossings * 2, path.size)
    }

    @Test
    fun unwrappedPolygon_antimeridianCrossing_keepsSingleOuterAndHoles() {
        // ±180 を跨ぐ外周 + 反対側経度表現の穴。分割版では穴が落ちるが、
        // unwrap 版では外周 1 リングのまま穴を保持できる
        val outer =
            listOf(
                GeoPoint(10.0, 170.0),
                GeoPoint(10.0, -170.0),
                GeoPoint(20.0, -170.0),
                GeoPoint(20.0, 170.0),
            )
        val hole =
            listOf(
                GeoPoint(14.0, -178.0),
                GeoPoint(14.0, -176.0),
                GeoPoint(16.0, -176.0),
                GeoPoint(16.0, -178.0),
            )
        val rings = buildUnwrappedPolygonRings(outer, listOf(hole), geodesic = false)
        assertEquals(1, rings.outerRings.size)
        assertEquals(1, rings.holeRings.size)

        val outerRing = rings.outerRings.first()
        for (i in 0 until outerRing.size - 1) {
            assertTrue(kotlin.math.abs(outerRing[i + 1].longitude - outerRing[i].longitude) < 180.0)
        }
        // 穴は外周と同じ連続座標系に配置される（外周の経度範囲内に収まる）
        val outerMin = outerRing.minOf { it.longitude }
        val outerMax = outerRing.maxOf { it.longitude }
        rings.holeRings.first().forEach { p ->
            assertTrue("hole lng=${p.longitude}", p.longitude in outerMin..outerMax)
        }
    }

    // ---- OverlayGeoJson: 従来の MapTiler/Longdo レンダラーの文字列組み立てと同一出力であること ----

    private fun legacyRingToJson(ring: List<GeoPointInterface>): String {
        val closed = if (ring.first() != ring.last()) ring + ring.first() else ring
        return closed.joinToString(separator = ",", prefix = "[", postfix = "]") { p ->
            "[${p.longitude},${p.latitude}]"
        }
    }

    @Test
    fun geoJson_multiLineString_matchesLegacyFormat() {
        val segments = buildPolylineSegments(listOf(tokyo, newYork), geodesic = true)
        val coordinates =
            segments.joinToString(separator = ",") { segment ->
                segment.joinToString(separator = ",", prefix = "[", postfix = "]") { p ->
                    "[${p.longitude},${p.latitude}]"
                }
            }
        val legacy =
            "{\"type\":\"Feature\",\"geometry\":" +
                "{\"type\":\"MultiLineString\",\"coordinates\":[$coordinates]},\"properties\":{}}"
        assertEquals(legacy, OverlayGeoJson.multiLineStringFeature(segments))
        assertNull(OverlayGeoJson.multiLineStringFeature(emptyList()))
    }

    @Test
    fun geoJson_singlePolygonWithHole_matchesLegacyFormat() {
        val outer =
            listOf(
                GeoPoint(35.0, 139.0),
                GeoPoint(35.0, 140.0),
                GeoPoint(36.0, 140.0),
                GeoPoint(36.0, 139.0),
            )
        val hole =
            listOf(
                GeoPoint(35.4, 139.4),
                GeoPoint(35.4, 139.6),
                GeoPoint(35.6, 139.6),
                GeoPoint(35.6, 139.4),
            )
        val rings = buildPolygonRings(outer, listOf(hole), geodesic = true)
        val ringJsons =
            (listOf(rings.outerRings.first()) + rings.holeRings).map { legacyRingToJson(it) }
        val legacy =
            "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":" +
                ringJsons.joinToString(separator = ",", prefix = "[", postfix = "]") +
                "},\"properties\":{}}"
        assertEquals(legacy, OverlayGeoJson.polygonFeature(rings))
    }

    @Test
    fun geoJson_multiPolygon_matchesLegacyFormat() {
        val outer =
            listOf(
                GeoPoint(10.0, 170.0),
                GeoPoint(10.0, -170.0),
                GeoPoint(20.0, -170.0),
                GeoPoint(20.0, 170.0),
            )
        val rings = buildPolygonRings(outer, emptyList(), geodesic = false)
        assertTrue(rings.outerRings.size >= 2)
        val legacy =
            "{\"type\":\"Feature\",\"geometry\":" +
                "{\"type\":\"MultiPolygon\",\"coordinates\":[" +
                rings.outerRings.joinToString(separator = ",") { "[${legacyRingToJson(it)}]" } +
                "]},\"properties\":{}}"
        assertEquals(legacy, OverlayGeoJson.polygonFeature(rings))
        assertNull(OverlayGeoJson.polygonFeature(PolygonRings(emptyList(), emptyList())))
    }
}
