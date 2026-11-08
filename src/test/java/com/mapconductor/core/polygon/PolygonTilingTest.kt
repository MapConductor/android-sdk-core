package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ポリゴンのタイル描画を支える部品。
 *
 * js-sdk-core の `PolygonTiling.test.mjs`、ios-sdk の `PolygonTilingTests` と 1 対 1 で対応する。
 * web の Encoded Polyline の 2 件は、サービスワーカーへ渡すための変換なので
 * Android には無い（タイルサーバーが同じプロセスにあり、状態をそのまま読む）。
 *
 * - しきい値: ポリゴン数か頂点数のどちらかが届けばタイル。判定はここ 1 か所。
 * - 穴のユニオンの近道: 交わらない穴はそのまま返す。道路網の区画（穴 80 個・穴の頂点
 *   8,000）で 1 個数秒かかっていた二乗の計算を、交わる穴があるときだけに絞る。
 */
class PolygonTilingTest {
    private fun p(
        latitude: Double,
        longitude: Double,
    ): GeoPointInterface = GeoPoint.fromLatLong(latitude, longitude)

    private fun square(
        lat: Double,
        lng: Double,
        size: Double,
    ): List<GeoPointInterface> = listOf(p(lat, lng), p(lat, lng + size), p(lat + size, lng + size), p(lat + size, lng))

    /** The same square wound clockwise, the way holes come out of the union. */
    private fun clockwise(ring: List<GeoPointInterface>): List<GeoPointInterface> = ring.reversed()

    @Test
    fun `ポリゴン数か頂点数のどちらかが届けばタイルにする`() {
        val o = PolygonTilingOptions.Default
        assertFalse(o.shouldUseTiles(1999, 49_999))
        assertTrue(o.shouldUseTiles(2000, 0))
        assertTrue(o.shouldUseTiles(10, 50_000))
        assertFalse(PolygonTilingOptions.Disabled.shouldUseTiles(100_000, 10_000_000))
    }

    @Test
    fun `geodesic・経度の幅 180 度以上・頂点 3 未満はタイルにしない`() {
        assertTrue(PolygonTileSet.canTile(PolygonState(points = square(0.0, 0.0, 1.0))))
        assertFalse(PolygonTileSet.canTile(PolygonState(points = square(0.0, 0.0, 1.0), geodesic = true)))
        assertFalse(PolygonTileSet.canTile(PolygonState(points = listOf(p(0.0, -100.0), p(0.0, 80.0), p(10.0, 0.0)))))
        assertTrue(PolygonTileSet.canTile(PolygonState(points = listOf(p(0.0, -90.0), p(0.0, 89.0), p(10.0, 0.0)))))
        assertFalse(PolygonTileSet.canTile(PolygonState(points = listOf(p(0.0, 0.0), p(1.0, 1.0)))))
    }

    @Test
    fun `交わらない穴は計算せずにそのまま返す`() {
        val holes = listOf(square(0.0, 0.0, 1.0), square(0.0, 2.0, 1.0), square(2.0, 0.0, 1.0)).map(::clockwise)
        assertSame(holes, unionHoleRings(holes))
    }

    /**
     * Android の union は穴を時計回りにそろえて返す（レンダラーが外周と逆巻きを期待する。
     * `PolygonHoleUnionTest.disjointSquares_remainSeparateWithSameAreas`）。近道でもそれは守る。
     * web の近道は向きをそろえずに返す。
     */
    @Test
    fun `近道でも、反時計回りの穴は時計回りにして返す`() {
        val holes = listOf(square(0.0, 0.0, 1.0), clockwise(square(0.0, 2.0, 1.0)))
        val result = unionHoleRings(holes)
        assertEquals(2, result.size)
        assertEquals(holes[0].reversed(), result[0])
        assertSame(holes[1], result[1])
    }

    @Test
    fun `重なる穴・中に入った穴・接する穴は従来どおり 1 つにまとめる`() {
        assertEquals("重なる", 1, unionHoleRings(listOf(square(0.0, 0.0, 2.0), square(1.0, 1.0, 2.0))).size)
        assertEquals("中に入った", 1, unionHoleRings(listOf(square(0.0, 0.0, 4.0), square(1.0, 1.0, 1.0))).size)
        assertEquals("辺で接する", 1, unionHoleRings(listOf(square(0.0, 0.0, 1.0), square(0.0, 1.0, 1.0))).size)
    }

    @Test
    fun `交わらない穴が多くても速い（穴 80 個 x 100 頂点）`() {
        val holes =
            List(80) { i ->
                val cx = (i % 10) * 3.0
                val cy = (i / 10) * 3.0
                List(100) { k ->
                    val a = -2 * PI * k / 100
                    p(cy + sin(a), cx + cos(a))
                }
            }
        val start = System.nanoTime()
        val result = unionHoleRings(holes)
        val ms = (System.nanoTime() - start) / 1e6
        assertSame(holes, result)
        assertTrue("$ms ms", ms < 200)
    }
}
