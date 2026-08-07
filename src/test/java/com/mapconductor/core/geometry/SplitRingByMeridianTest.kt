package com.mapconductor.core.geometry

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SplitRingByMeridianTest {
    @Test
    fun nonCrossingRing_returnsSingleFragmentUnchanged() {
        val ring = circleToRing(GeoPoint(35.0, 139.0), 10_000.0, geodesic = true).map { it.normalize() }
        val fragments = splitRingByMeridian(ring, geodesic = true)
        assertEquals(1, fragments.size)
        assertEquals(ring, fragments.first())
    }

    @Test
    fun antimeridianCrossingCircle_splitsIntoExactlyTwoPieces() {
        // ±180 を 2 回跨ぐ円 → 東西ちょうど 2 ピースになる（開いたパス用の
        // splitByMeridian では最初と最後の断片が分かれて 3 断片＝くさび欠けになる）
        val ring = circleToRing(GeoPoint(10.0, 178.0), 500_000.0, geodesic = true).map { it.normalize() }
        val fragments = splitRingByMeridian(ring, geodesic = true)
        assertEquals(2, fragments.size)

        fragments.forEach { fragment ->
            assertTrue(fragment.size >= 3)
            // 各断片は連続（±360 ジャンプが無い）
            for (i in 0 until fragment.size - 1) {
                val diff = abs(fragment[i + 1].longitude - fragment[i].longitude)
                assertTrue("jump=$diff", diff <= 180.0)
            }
        }
        // 全頂点数 = 元リング + 交差ごとの挿入点（2 交差 × 2 点）
        assertEquals(ring.size + 4, fragments.sumOf { it.size })
        // 東側と西側のピースに分かれている
        val sides = fragments.map { frag -> frag.all { it.longitude >= 0 } }
        assertTrue(sides.contains(true) && sides.contains(false))
    }

    @Test
    fun planarCrossingCircle_alsoSplitsIntoTwoPieces() {
        val ring = circleToRing(GeoPoint(-20.0, -179.0), 300_000.0, geodesic = false).map { it.normalize() }
        val fragments = splitRingByMeridian(ring, geodesic = false)
        assertEquals(2, fragments.size)
    }

    @Test
    fun degenerateInput_passesThrough() {
        assertTrue(splitRingByMeridian(emptyList(), geodesic = true).isEmpty())
        val two = listOf(GeoPoint(0.0, 179.0), GeoPoint(0.0, -179.0))
        assertEquals(listOf(two), splitRingByMeridian(two, geodesic = true))
    }
}
