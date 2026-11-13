package com.mapconductor.core.polyline

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
 * ポリラインのタイル描画を支える部品（画素を見ない部分）。
 *
 * js-sdk-core の `PolylineTiling.test.mjs`、ios-sdk の `PolylineTilingTests` と 1 対 1 で対応する。
 * 画素そのものは端末で見る（androidTest の `PolylineTileRendererDeviceTest`）。
 *
 * - しきい値: 本数か点の数のどちらかが届けばタイル。判定はここ 1 か所。
 * - タイルにできないもの: geodesic、経度の幅 180 度以上、2 点未満。
 * - ズームごとの簡略化: 画素格子に丸め、続く重複を捨て、半画素の Douglas-Peucker（両端は残す）。
 *   1 点に潰れたら点。
 */
class PolylineTilingTest {
    private fun p(
        latitude: Double,
        longitude: Double,
    ): GeoPointInterface = GeoPoint.fromLatLong(latitude, longitude)

    @Test
    fun `本数か点の数のどちらかが届けばタイルにする`() {
        val o = PolylineTilingOptions.Default
        assertFalse(o.shouldUseTiles(1999, 49_999))
        assertTrue(o.shouldUseTiles(2000, 0))
        assertTrue(o.shouldUseTiles(10, 50_000))
        assertFalse(PolylineTilingOptions.Disabled.shouldUseTiles(100_000, 10_000_000))
    }

    @Test
    fun `geodesic・経度の幅 180 度以上・2 点未満はタイルにしない`() {
        assertTrue(PolylineTileSet.canTile(PolylineState(points = listOf(p(0.0, 0.0), p(1.0, 1.0)))))
        assertFalse(PolylineTileSet.canTile(PolylineState(points = listOf(p(0.0, 0.0), p(1.0, 1.0)), geodesic = true)))
        assertFalse(PolylineTileSet.canTile(PolylineState(points = listOf(p(0.0, -100.0), p(0.0, 80.0)))))
        assertTrue(PolylineTileSet.canTile(PolylineState(points = listOf(p(0.0, -90.0), p(0.0, 89.0)))))
        assertFalse(PolylineTileSet.canTile(PolylineState(points = listOf(p(0.0, 0.0)))))
        assertFalse(PolylineTileSet.canTile(PolylineState(points = emptyList())))
    }

    @Test
    fun `点の数は全ポリラインの合計`() {
        val a = PolylineState(points = listOf(p(0.0, 0.0), p(1.0, 1.0), p(2.0, 2.0)))
        val b = PolylineState(points = listOf(p(0.0, 0.0), p(1.0, 1.0)))
        assertEquals(5, PolylineTileSet.vertexCount(listOf(a, b)))
    }

    @Test
    fun `タイル用の線は開いた経路のまま、外接矩形を持つ`() {
        assertNull(TilePolyline.from(PolylineState(points = listOf(p(0.0, 0.0)))))
        val line = TilePolyline.from(PolylineState(points = listOf(p(0.0, 0.0), p(0.0, 90.0), p(0.0, 0.0))))!!
        // 閉じない: 3 点のまま
        assertEquals(6, line.path.size)
        assertArrayEquals(doubleArrayOf(0.5, 0.5, 0.75, 0.5), line.bbox, 1e-12)
    }

    @Test
    fun `点は画素格子に丸め、続く重複と直線上の点を捨て、両端は残す`() {
        // 世界 1000 画素。経度 0 度 = x 500
        val scale = 1000.0
        val line =
            TilePolyline(
                path =
                    doubleArrayOf(
                        0.500,
                        0.500,
                        0.5004,
                        0.5001, // (500, 500) に丸まる
                        0.505,
                        0.500, // 直線上
                        0.510,
                        0.500,
                        0.510,
                        0.510, // 曲がる
                    ),
                bbox = doubleArrayOf(0.5, 0.5, 0.51, 0.51),
                color = 0xFF000000.toInt(),
                strokeWidth = 2f,
            )
        val shape = simplifyPolyline(line, scale)
        assertTrue(shape is PolylineTileShape.Path)
        assertArrayEquals(
            doubleArrayOf(500.0, 500.0, 510.0, 500.0, 510.0, 510.0),
            (shape as PolylineTileShape.Path)
                .points,
            0.0,
        )
    }

    @Test
    fun `直線は両端の 2 点になる`() {
        val line = TilePolyline.from(PolylineState(points = List(20) { i -> p(0.0, i * 0.5) }))!!
        val shape = simplifyPolyline(line, 256.0 * 64) as PolylineTileShape.Path
        assertEquals(4, shape.points.size)
    }

    @Test
    fun `1 画素に満たない線は点になる`() {
        val line = TilePolyline.from(PolylineState(points = listOf(p(0.0, 0.0), p(0.0, 0.001))))!!
        // z0: 世界が 256 画素。0.001 度は 0.0007 画素
        val shape = simplifyPolyline(line, 256.0)
        assertTrue(shape is PolylineTileShape.Dot)
        shape as PolylineTileShape.Dot
        assertEquals(128.0, shape.x, 0.01)
        assertEquals(128.0, shape.y, 0.01)
    }

    @Test
    fun `往復する短い線は、外接矩形が 1 画素以上なら経路、未満なら点`() {
        val line =
            TilePolyline(
                path = doubleArrayOf(0.5, 0.5, 0.5012, 0.5, 0.5001, 0.5),
                bbox = doubleArrayOf(0.5, 0.5, 0.5012, 0.5),
                color = 0xFF000000.toInt(),
                strokeWidth = 2f,
            )
        // 世界 1000 画素: 500.0 → 500, 501.2 → 501, 500.1 → 500。2 画素なので経路
        assertTrue(simplifyPolyline(line, 1000.0) is PolylineTileShape.Path)
        // 世界 800 画素: 外接矩形 0.96 px < 1 なので点
        assertTrue(simplifyPolyline(line, 800.0) is PolylineTileShape.Dot)
    }

    @Test
    fun `見えない線（透明・幅 0）は余白にも数えない`() {
        val transparent =
            TilePolyline
                .from(PolylineState(points = listOf(p(0.0, 0.0), p(1.0, 1.0)), strokeColor = Color.Transparent))!!
        assertFalse(transparent.visible)
        assertEquals(0f, PolylineTilePainter.strokeWidth(transparent))
        val visible = TilePolyline.from(PolylineState(points = listOf(p(0.0, 0.0), p(1.0, 1.0)), strokeWidth = 6.dp))!!
        assertNotNull(visible)
        assertEquals(6f, PolylineTilePainter.strokeWidth(visible))
    }
}
