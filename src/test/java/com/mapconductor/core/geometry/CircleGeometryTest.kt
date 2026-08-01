package com.mapconductor.core.geometry

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.normalize
import com.mapconductor.core.spherical.GeographicLibCalculator
import com.mapconductor.core.spherical.Spherical
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircleGeometryTest {
    private val tokyo = GeoPoint(35.68, 139.76)

    @Test
    fun geodesicRing_pointsAreEquidistantFromCenter() {
        val radius = 50_000.0
        val ring = circleToRing(tokyo, radius, geodesic = true)
        assertEquals(DEFAULT_CIRCLE_SEGMENTS, ring.size)
        ring.forEach { p ->
            // 球面 offset で生成しているため球面距離では厳密に一致する
            assertEquals(radius, Spherical.computeDistanceBetween(tokyo, p), 1.0)
        }
    }

    @Test
    fun planarRing_approximatesRadiusAtMidLatitude() {
        val radius = 10_000.0
        val ring = circleToRing(tokyo, radius, geodesic = false)
        assertEquals(DEFAULT_CIRCLE_SEGMENTS, ring.size)
        ring.forEach { p ->
            val d = GeographicLibCalculator.computeDistanceBetween(tokyo, p)
            // 局所平面近似なので 1% 以内で半径に一致すること
            assertTrue("distance=$d", d in radius * 0.99..radius * 1.01)
        }
    }

    @Test
    fun ringNearAntimeridian_isUnwrappedAndContinuous() {
        // 経度は中心まわりに連続化（unwrap）され、±180 を跨いでも飛ばないこと
        // （GL 系 SDK が分割なしで 1 枚のポリゴンとして描画できる形）
        val center = GeoPoint(21.3, -157.85)
        val ring = circleToRing(center, 2_800_000.0, geodesic = true)
        assertTrue(ring.isNotEmpty())
        for (i in 0 until ring.size - 1) {
            val diff = kotlin.math.abs(ring[i + 1].longitude - ring[i].longitude)
            assertTrue("jump=$diff", diff < 180.0)
        }
        ring.forEach { p ->
            assertTrue("lat=${p.latitude}", p.latitude in -90.0..90.0)
        }
    }

    @Test
    fun normalizedAndSplitRing_staysWithinLongitudeRange() {
        // TomTom クラッシュの再現条件: ±180 付近の中心 + 大きな半径。
        // normalize + splitRingByMeridian（TomTom 経路）で全断片が [-180, 180] に収まること
        val center = GeoPoint(21.3, -157.85)
        val fragments =
            splitRingByMeridian(
                circleToRing(center, 2_800_000.0, geodesic = true).map { it.normalize() },
                geodesic = true,
            )
        assertTrue(fragments.isNotEmpty())
        fragments.forEach { fragment ->
            fragment.forEach { p ->
                assertTrue("lng=${p.longitude}", p.longitude in -180.0..180.0)
            }
        }
    }

    @Test
    fun degenerateInputs_returnEmpty() {
        assertTrue(circleToRing(tokyo, 0.0, geodesic = true).isEmpty())
        assertTrue(circleToRing(tokyo, -1.0, geodesic = false).isEmpty())
        assertTrue(circleToRing(tokyo, 100.0, geodesic = true, segments = 2).isEmpty())
    }

    @Test
    fun nearPole_planarRingStaysFinite() {
        val ring = circleToRing(GeoPoint(89.9999, 0.0), 1_000.0, geodesic = false)
        ring.forEach { p ->
            assertTrue(p.latitude.isFinite() && p.longitude.isFinite())
            assertTrue(p.latitude in -90.0..90.0)
        }
    }
}
