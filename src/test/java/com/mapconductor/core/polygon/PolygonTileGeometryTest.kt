package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * タイルに描く前の幾何: 巻きの正規化、ズームごとの簡略化、グリッド索引。
 *
 * js-sdk-core の tile-sw.js「Polygon tiles」節と同じ手順であることを固定する。
 * 画素そのものは端末で見る（androidTest の `PolygonTileRendererDeviceTest`）。
 */
class PolygonTileGeometryTest {
    private fun p(
        latitude: Double,
        longitude: Double,
    ): GeoPointInterface = GeoPoint.fromLatLong(latitude, longitude)

    private fun square(
        lat: Double,
        lng: Double,
        size: Double,
    ): List<GeoPointInterface> = listOf(p(lat, lng), p(lat, lng + size), p(lat + size, lng + size), p(lat + size, lng))

    @Test
    fun `外周は正、穴は負の巻きにそろう（与えた向きによらない）`() {
        val outer = square(0.0, 0.0, 4.0)
        val hole = square(1.0, 1.0, 1.0)
        for (o in listOf(outer, outer.reversed())) {
            for (h in listOf(hole, hole.reversed())) {
                val polygon = TilePolygon.from(PolygonState(points = o, holes = listOf(h)))!!
                assertTrue(signedArea(polygon.rings[0]) > 0)
                assertTrue(signedArea(polygon.rings[1]) < 0)
            }
        }
    }

    @Test
    fun `外接矩形は Web Mercator の 0 から 1 で、北が小さい y`() {
        val polygon = TilePolygon.from(PolygonState(points = square(0.0, 0.0, 10.0)))!!
        assertEquals(0.5, polygon.bbox[0], 1e-12)
        assertEquals((10.0 + 180) / 360, polygon.bbox[2], 1e-12)
        assertEquals(0.5, polygon.bbox[3], 1e-12)
        assertTrue(polygon.bbox[1] < 0.5)
    }

    @Test
    fun `外周が 3 点未満なら描かない、穴が 3 点未満なら穴だけ捨てる`() {
        assertNull(TilePolygon.from(PolygonState(points = listOf(p(0.0, 0.0), p(1.0, 1.0)))))
        val polygon = TilePolygon.from(PolygonState(points = square(0.0, 0.0, 4.0), holes = listOf(listOf(p(1.0, 1.0)))))!!
        assertEquals(1, polygon.rings.size)
    }

    @Test
    fun `頂点は画素格子に丸め、続く重複を捨て、半画素の Douglas-Peucker で間引く`() {
        val scale = 1000.0
        // 1 画素幅の正方形の辺上に、同じ画素に丸まる点と、直線上の点を足す
        val ring =
            doubleArrayOf(
                0.0,
                0.0,
                0.0004,
                0.0001, // (0, 0) に丸まる
                0.005,
                0.0, // 直線上
                0.010,
                0.0,
                0.010,
                0.010,
                0.0,
                0.010,
            )
        val simplified = simplifyRing(ring, scale)!!
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0), simplified, 0.0)
    }

    @Test
    fun `半画素未満のずれは捨て、それを超えるずれは残す`() {
        val straight = douglasPeucker(doubleArrayOf(0.0, 0.0, 5.0, 0.4, 10.0, 0.0), 3, DP_TOLERANCE_PX)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 10.0, 0.0), straight, 0.0)
        val bent = douglasPeucker(doubleArrayOf(0.0, 0.0, 5.0, 1.0, 10.0, 0.0), 3, DP_TOLERANCE_PX)
        assertEquals(6, bent.size)
    }

    @Test
    fun `1 画素に満たないポリゴンは 1 画素の点になる`() {
        val polygon = TilePolygon.from(PolygonState(points = square(0.0, 0.0, 0.001)))!!
        // z0: 世界が 256 画素。0.001 度は 0.0007 画素
        val shape = simplifyPolygon(polygon, 256.0)
        assertTrue(shape is TileShape.Dot)
        shape as TileShape.Dot
        assertEquals(128.0, shape.x, 0.0)
        assertEquals(127.0, shape.y, 0.0)
    }

    @Test
    fun `画面上 4 画素未満のポリゴンは枠線を描かない`() {
        val polygon = TilePolygon.from(PolygonState(points = square(0.0, 0.0, 1.0)))!!
        // 1 度は 256 * 2^z / 360 画素。z2: 2.8 px、z3: 5.7 px
        val small = simplifyPolygon(polygon, 256.0 * 4) as TileShape.Rings
        val large = simplifyPolygon(polygon, 256.0 * 8) as TileShape.Rings
        assertFalse(small.outlined)
        assertTrue(large.outlined)
    }

    @Test
    fun `索引は外接矩形が重なるものだけを、重複なく描く順に返す`() {
        val states =
            listOf(
                square(0.0, 0.0, 0.01), // 0
                square(0.0, 1.0, 0.01), // 1: 離れている
                square(0.0, 0.005, 0.2), // 2: 複数のセルにまたがる
                square(-60.0, -100.0, 120.0), // 3: 大きい（every query）
            )
        val polygons = states.map { TilePolygon.from(PolygonState(points = it)) }.toTypedArray<TilePolygon?>()
        val index = PolygonGridIndex.build(polygons)
        val b0 = polygons[0]!!.bbox
        assertArrayEquals(intArrayOf(0, 2, 3), index.query(polygons, b0[0], b0[1], b0[2], b0[3]))
        val b1 = polygons[1]!!.bbox
        assertArrayEquals(intArrayOf(1, 3), index.query(polygons, b1[0], b1[1], b1[2], b1[3]))
        // 消したポリゴン（null）は返さない
        polygons[0] = null
        assertArrayEquals(intArrayOf(2, 3), index.query(polygons, b0[0], b0[1], b0[2], b0[3]))
    }

    @Test
    fun `測地線と、経度の幅が 180 度以上のポリゴンはタイルにしない`() {
        assertTrue(PolygonTileSet.canTile(PolygonState(points = square(0.0, 0.0, 1.0))))
        assertFalse(PolygonTileSet.canTile(PolygonState(points = square(0.0, 0.0, 1.0), geodesic = true)))
        assertFalse(PolygonTileSet.canTile(PolygonState(points = square(0.0, -90.0, 180.0))))
        assertFalse(PolygonTileSet.canTile(PolygonState(points = listOf(p(0.0, 0.0), p(1.0, 1.0)))))
    }

    @Test
    fun `頂点数は外周と穴の合計`() {
        val state = PolygonState(points = square(0.0, 0.0, 4.0), holes = listOf(square(1.0, 1.0, 1.0)))
        assertEquals(16, PolygonTileSet.vertexCount(listOf(state, state)))
        assertNotNull(TilePolygon.from(state))
    }
}
