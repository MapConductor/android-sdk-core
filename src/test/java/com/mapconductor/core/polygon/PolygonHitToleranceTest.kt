package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A tap counts as on a polygon's edge only when it is within `eps` (1e-6°,
 * about 0.1 m) of that edge. Taps outside the polygon's bounding box never get
 * that far, so the tolerance only matters on and just inside the outline.
 *
 * js-sdk-core の `PolygonHitTolerance.test.mjs`、ios-sdk の `PolygonHitToleranceTests` と
 * 1 対 1 で対応する。
 *
 * 辺上の判定が度の 2 乗の量（内積）を 1e-6 と比べていて、短い辺から約 100 m 以内の
 * タップをすべて「辺上」として拾っていた。建物の輪郭のように辺が数 m のポリゴンを
 * 並べると、タップした建物ではなく、リストで先にある近所の建物が返る。大きな図形では
 * 起きないので、サンプルの六角形では見えなかった。
 */
class PolygonHitToleranceTest {
    // ボストン中心部の、一辺が約 10 m の建物
    private val building =
        listOf(
            GeoPoint(42.3550, -71.0600),
            GeoPoint(42.3550, -71.0599),
            GeoPoint(42.3551, -71.0599),
            GeoPoint(42.3551, -71.0600),
            GeoPoint(42.3550, -71.0600),
        )

    private fun managerWith(vararg states: PolygonState): PolygonManager<Unit> =
        PolygonManager<Unit>().apply {
            states.forEach { registerEntity(PolygonEntity(polygon = Unit, state = it)) }
        }

    @Test
    fun `50 m 離れたタップは、辺の短い建物に当たらない`() {
        val manager = managerWith(PolygonState(points = building, id = "small"))
        // 建物の東の辺から東へ約 50 m（経度 0.0006°）
        assertNull(manager.find(GeoPoint(42.35505, -71.0593)))
        // 北へ約 50 m
        assertNull(manager.find(GeoPoint(42.3556, -71.05995)))
    }

    @Test
    fun `内側のタップと辺の上のタップは当たり、0点6 m 外れたタップは当たらない`() {
        val manager = managerWith(PolygonState(points = building, id = "small"))
        assertEquals("small", manager.find(GeoPoint(42.35505, -71.05995))?.state?.id)
        assertEquals("南の辺の上", "small", manager.find(GeoPoint(42.3550, -71.05995))?.state?.id)
        assertNull("南の辺から外へ約 0.6 m", manager.find(GeoPoint(42.354995, -71.05995)))
    }

    @Test
    fun `近所の建物ではなく、タップした建物が返る`() {
        // 西隣（約 20 m 西）にもう 1 棟。リストでは先に入れる
        val west = building.map { GeoPoint(it.latitude, it.longitude - 0.00025) }
        val manager =
            managerWith(
                PolygonState(points = west, id = "west"),
                PolygonState(points = building, id = "tapped"),
            )
        assertEquals("tapped", manager.find(GeoPoint(42.35505, -71.05995))?.state?.id)
    }
}
