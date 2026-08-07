package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [bridgeHolesIntoSingleRing] の性質テスト（ios-sdk `HoleBridgeTests.swift` と同じ観点）。
 * ブリッジ後の単一リングは「外周の内側かつ全穴の外側」の点を含み、穴の内側の点を含まない
 * （偶奇規則）ことを検証する。
 */
class HoleBridgeTest {
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

    /**
     * 偶奇規則（ray casting）。ブリッジ済みリングは弱単純ポリゴンなので偶奇規則で
     * 塗り領域が決まる（TomTom 等の塗りと同じ判定）。
     */
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

    @Test
    fun noHoles_returnsOuterUnchanged() {
        val outer = rect(0.0, 0.0, 10.0, 10.0)
        val bridged = bridgeHolesIntoSingleRing(outer, emptyList())
        assertEquals(outer, bridged)
    }

    @Test
    fun singleHole_isCutOut() {
        val outer = rect(0.0, 0.0, 10.0, 10.0)
        val hole = rect(4.0, 4.0, 6.0, 6.0)
        val bridged = bridgeHolesIntoSingleRing(outer, listOf(hole))

        // 元の頂点 + 穴頂点 + 橋の複製 2 点
        assertEquals(outer.size + hole.size + 2, bridged.size)

        assertFalse(evenOddInside(5.0, 5.0, bridged)) // 穴の中心は塗られない
        assertTrue(evenOddInside(2.0, 2.0, bridged)) // 穴の外（外周の内側）は塗られる
        assertTrue(evenOddInside(8.0, 8.0, bridged))
        assertFalse(evenOddInside(11.0, 11.0, bridged)) // 外周の外側は塗られない
    }

    @Test
    fun twoDisjointHoles_areBothCutOut() {
        val outer = rect(0.0, 0.0, 10.0, 10.0)
        val bridged =
            bridgeHolesIntoSingleRing(
                outer,
                listOf(rect(1.0, 1.0, 3.0, 3.0), rect(6.0, 6.0, 8.0, 8.0)),
            )

        assertFalse(evenOddInside(2.0, 2.0, bridged))
        assertFalse(evenOddInside(7.0, 7.0, bridged))
        assertTrue(evenOddInside(5.0, 5.0, bridged))
        assertTrue(evenOddInside(9.0, 9.0, bridged))
    }

    @Test
    fun windingDirection_doesNotChangeResult() {
        val outer = rect(0.0, 0.0, 10.0, 10.0)
        val fromCw = bridgeHolesIntoSingleRing(outer, listOf(rect(4.0, 4.0, 6.0, 6.0).reversed()))
        val fromCcw = bridgeHolesIntoSingleRing(outer, listOf(rect(4.0, 4.0, 6.0, 6.0)))

        for ((lat, lng) in listOf(5.0 to 5.0, 2.0 to 2.0, 8.0 to 8.0)) {
            assertEquals(evenOddInside(lat, lng, fromCw), evenOddInside(lat, lng, fromCcw))
        }
        assertFalse(evenOddInside(5.0, 5.0, fromCw))
    }

    @Test
    fun closedRingInput_isHandled() {
        val outer = rect(0.0, 0.0, 10.0, 10.0).let { it + it.first() }
        val hole = rect(4.0, 4.0, 6.0, 6.0).let { it + it.first() }
        val bridged = bridgeHolesIntoSingleRing(outer, listOf(hole))

        assertFalse(evenOddInside(5.0, 5.0, bridged))
        assertTrue(evenOddInside(2.0, 2.0, bridged))
    }

    @Test
    fun degenerateHole_isIgnored() {
        val outer = rect(0.0, 0.0, 10.0, 10.0)
        val degenerate =
            listOf(
                GeoPoint(latitude = 5.0, longitude = 5.0),
                GeoPoint(latitude = 6.0, longitude = 6.0),
            )
        val bridged = bridgeHolesIntoSingleRing(outer, listOf(degenerate))

        assertEquals(outer.size, bridged.size)
        assertTrue(evenOddInside(5.0, 5.5, bridged))
    }

    /**
     * separation > 0 のとき、リングは厳密に単純（座標が一致する往復エッジなし）になり、
     * 穴は引き続き抜ける。
     */
    @Test
    fun separation_producesStrictlySimpleRing() {
        val outer = rect(0.0, 0.0, 10.0, 10.0)
        val hole = rect(4.0, 4.0, 6.0, 6.0)
        val bridged = bridgeHolesIntoSingleRing(outer, listOf(hole), separation = 1e-6)

        assertEquals(outer.size + hole.size + 2, bridged.size)
        assertFalse(evenOddInside(5.0, 5.0, bridged))
        assertTrue(evenOddInside(2.0, 2.0, bridged))

        // 座標が完全に一致する頂点（ゼロ幅橋の複製）が存在しない
        val keys = bridged.map { "${it.latitude},${it.longitude}" }
        assertEquals(keys.size, keys.toSet().size)
    }

    /** separation = 0（既定）は従来どおり、幅ゼロの橋＝座標の重複する頂点を作る。 */
    @Test
    fun zeroSeparation_keepsZeroWidthBridge() {
        val outer = rect(0.0, 0.0, 10.0, 10.0)
        val hole = rect(4.0, 4.0, 6.0, 6.0)
        val bridged = bridgeHolesIntoSingleRing(outer, listOf(hole))

        val keys = bridged.map { "${it.latitude},${it.longitude}" }
        assertEquals(2, keys.size - keys.toSet().size)
    }

    /** 世界マスク外周（サンプルページ相当）。 */
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

    /** 札幌近郊の三角形の穴。 */
    private fun sapporoTriangleHole(): List<GeoPointInterface> =
        listOf(
            GeoPoint(latitude = 43.10086924222251, longitude = 141.35290903949243),
            GeoPoint(latitude = 43.04444342582366, longitude = 141.4118953480885),
            GeoPoint(latitude = 43.05060149394299, longitude = 141.30656265416695),
        )

    private fun maxAbsLngStep(ring: List<GeoPointInterface>): Double {
        var maxStep = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            maxStep = maxOf(maxStep, abs(b.longitude - a.longitude))
        }
        return maxStep
    }

    @Test
    fun worldMaskWithTriangleHole_cutsHoleOut() {
        val bridged = bridgeHolesIntoSingleRing(worldMaskOuter(), listOf(sapporoTriangleHole()))

        assertFalse(evenOddInside(43.0653, 141.3571, bridged)) // 穴の重心は抜ける
        assertTrue(evenOddInside(35.0, 139.0, bridged)) // 穴の外は塗られる
        assertTrue(evenOddInside(-30.0, 20.0, bridged))
    }

    /**
     * wrap-aware: 世界マスク外周 + 東経の穴では、西向きの橋が経度 180° 超を跨ぐため、
     * 東向きに張り替えて全エッジの経度ステップを 180° 以下に抑える。穴は引き続き抜ける。
     */
    @Test
    fun wrapAware_keepsLngStepsUnder180() {
        val outer = worldMaskOuter()
        val hole = sapporoTriangleHole()

        // 標準（西向き）は 180° 超のエッジを含む
        val west = bridgeHolesIntoSingleRing(outer, listOf(hole))
        assertTrue(maxAbsLngStep(west) > 180.0)

        // wrap-aware は全エッジ 180° 以下
        val bridged = bridgeHolesIntoSingleRingWrapAware(outer, listOf(hole), separation = 1e-6)
        assertTrue(maxAbsLngStep(bridged) <= 180.0)

        assertFalse(evenOddInside(43.0653, 141.3571, bridged))
        assertTrue(evenOddInside(35.0, 139.0, bridged))
        assertTrue(evenOddInside(-30.0, 20.0, bridged))
    }

    /** 180° 超のエッジが出ない入力では、wrap-aware でも標準と同じ結果を返す。 */
    @Test
    fun wrapAware_isIdenticalWhenNoWrapNeeded() {
        val outer = rect(0.0, 0.0, 10.0, 10.0)
        val hole = rect(4.0, 4.0, 6.0, 6.0)

        val plain = bridgeHolesIntoSingleRing(outer, listOf(hole))
        val wrapAware = bridgeHolesIntoSingleRingWrapAware(outer, listOf(hole))

        assertEquals(plain.map { it.latitude to it.longitude }, wrapAware.map { it.latitude to it.longitude })
    }
}
