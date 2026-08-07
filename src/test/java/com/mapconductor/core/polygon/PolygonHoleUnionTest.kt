package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自前の平面 union（react-sdk PolygonUnion.ts の移植）の性質テスト。
 */
class PolygonHoleUnionTest {
    /** (lat, lng) ペアの列からリングを作る。 */
    private fun ring(vararg latLng: Pair<Double, Double>): List<GeoPointInterface> =
        latLng.map { GeoPoint.fromLatLong(latitude = it.first, longitude = it.second) }

    /** 矩形リング（開いたまま）。 */
    private fun rect(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): List<GeoPointInterface> =
        ring(
            south to west,
            south to east,
            north to east,
            north to west,
        )

    private fun signedAreaLonLat(ring: List<GeoPointInterface>): Double {
        var area = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            area += (a.longitude * b.latitude) - (b.longitude * a.latitude)
        }
        return area / 2.0
    }

    /** 偶奇規則の点包含（テスト用）。 */
    private fun pointInRing(
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

    private fun insideAny(
        lat: Double,
        lng: Double,
        rings: List<List<GeoPointInterface>>,
    ): Boolean = rings.any { pointInRing(lat, lng, it) }

    @Test
    fun twoOverlappingSquares_mergeIntoOneClockwiseRing() {
        val a = rect(0.0, 0.0, 2.0, 2.0)
        val b = rect(1.0, 1.0, 3.0, 3.0)
        val merged = unionHoleRings(listOf(a, b))

        assertEquals(1, merged.size)
        val out = merged.first()
        // 穴は時計回りへ正規化される
        assertTrue("winding should be clockwise", signedAreaLonLat(out) < 0.0)
        // 両方の矩形の中心を含む
        assertTrue(pointInRing(0.5, 0.5, out))
        assertTrue(pointInRing(2.5, 2.5, out))
        // 面積 = 4 + 4 - 1(重なり) = 7
        assertEquals(7.0, kotlin.math.abs(signedAreaLonLat(out)), 1e-6)
    }

    @Test
    fun threeSquareChain_mergesIntoOneRing() {
        val a = rect(0.0, 0.0, 2.0, 2.0)
        val b = rect(0.5, 1.5, 1.5, 3.5)
        val c = rect(0.0, 3.0, 2.0, 5.0)
        val merged = unionHoleRings(listOf(a, b, c))
        assertEquals(1, merged.size)
        val out = merged.first()
        assertTrue(pointInRing(1.0, 1.0, out))
        assertTrue(pointInRing(1.0, 2.5, out))
        assertTrue(pointInRing(1.0, 4.0, out))
    }

    @Test
    fun disjointSquares_remainSeparateWithSameAreas() {
        val a = rect(0.0, 0.0, 1.0, 1.0)
        val b = rect(5.0, 5.0, 6.0, 6.0)
        val merged = unionHoleRings(listOf(a, b))
        assertEquals(2, merged.size)
        val areas = merged.map { kotlin.math.abs(signedAreaLonLat(it)) }.sorted()
        assertEquals(1.0, areas[0], 1e-6)
        assertEquals(1.0, areas[1], 1e-6)
        merged.forEach { assertTrue(signedAreaLonLat(it) < 0.0) }
    }

    @Test
    fun containedSquare_isAbsorbedByOuter() {
        val outer = rect(0.0, 0.0, 4.0, 4.0)
        val inner = rect(1.0, 1.0, 2.0, 2.0)
        val merged = unionHoleRings(listOf(outer, inner))
        assertEquals(1, merged.size)
        assertEquals(16.0, kotlin.math.abs(signedAreaLonLat(merged.first())), 1e-6)
    }

    @Test
    fun identicalSquares_collapseToOne() {
        val a = rect(0.0, 0.0, 2.0, 2.0)
        val merged = unionHoleRings(listOf(a, a.toList()))
        assertEquals(1, merged.size)
        assertEquals(4.0, kotlin.math.abs(signedAreaLonLat(merged.first())), 1e-6)
    }

    @Test
    fun edgeSharingSquares_mergeWithoutSlit() {
        // 右辺と左辺を完全共有する 2 矩形 → 1 リング、面積は合算
        val a = rect(0.0, 0.0, 2.0, 2.0)
        val b = rect(0.0, 2.0, 2.0, 4.0)
        val merged = unionHoleRings(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals(8.0, kotlin.math.abs(signedAreaLonLat(merged.first())), 1e-6)
    }

    @Test
    fun farFromOrigin_tokyoCoordinates_keepPrecision() {
        // 東京近辺（lon≈139.7）の小さな矩形同士
        val a = rect(35.6800, 139.7600, 35.6820, 139.7620)
        val b = rect(35.6810, 139.7610, 35.6830, 139.7630)
        val merged = unionHoleRings(listOf(a, b))
        assertEquals(1, merged.size)
        val out = merged.first()
        assertTrue(pointInRing(35.6810, 139.7610, out))
        assertTrue(pointInRing(35.6825, 139.7625, out))
        val expected = 2.0e-3 * 2.0e-3 * 2.0 - 1.0e-3 * 1.0e-3
        assertEquals(expected, kotlin.math.abs(signedAreaLonLat(out)), expected * 1e-4)
    }

    @Test
    fun selfIntersectingBowtie_doesNotCrash() {
        // 自己交差リング（蝶ネクタイ）+ 重なる矩形。クラッシュせず有限のリングを返すこと
        val bowtie =
            ring(
                0.0 to 0.0,
                2.0 to 2.0,
                0.0 to 2.0,
                2.0 to 0.0,
            )
        val square = rect(0.5, 0.5, 1.5, 1.5)
        val merged = unionHoleRings(listOf(bowtie, square))
        assertTrue(merged.isNotEmpty())
        merged.forEach { r ->
            assertTrue(r.size >= 3)
            r.forEach {
                assertTrue(it.latitude.isFinite() && it.longitude.isFinite())
            }
        }
    }

    @Test
    fun degenerateInputs_returnOriginalInstance() {
        val single = listOf(rect(0.0, 0.0, 1.0, 1.0))
        assertSame(single, unionHoleRings(single))

        val empty = emptyList<List<GeoPointInterface>>()
        assertSame(empty, unionHoleRings(empty))

        // 全リング縮退（3 点未満）→ 入力のまま
        val degenerate = listOf(ring(0.0 to 0.0, 1.0 to 1.0), ring(2.0 to 2.0))
        assertSame(degenerate, unionHoleRings(degenerate))
    }

    @Test
    fun sampledMembership_matchesInputCoverage() {
        // 結合の前後で「いずれかの穴の内部か」の判定がサンプル格子上で一致すること
        // （偶奇規則の XOR 打ち消しが解消される重なり領域を除き、被覆は保存される）
        val holes =
            listOf(
                rect(0.0, 0.0, 2.0, 2.0),
                rect(1.0, 1.0, 3.0, 3.0),
                rect(10.0, 10.0, 11.0, 11.0),
            )
        val merged = unionHoleRings(holes)
        var samples = 0
        var lat = -0.5
        while (lat <= 11.5) {
            var lng = -0.5
            while (lng <= 11.5) {
                // 境界上を避けるため半端な座標のみ標本化
                val expected = holes.count { pointInRing(lat, lng, it) } > 0
                val actual = insideAny(lat, lng, merged)
                assertEquals("at ($lat, $lng)", expected, actual)
                samples++
                lng += 0.37
            }
            lat += 0.37
        }
        assertTrue(samples > 500)
    }
}
