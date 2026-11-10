package com.mapconductor.core.polyline

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.shapetile.ShapeTileRenderer
import com.mapconductor.core.tileserver.TileRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.runBlocking

/**
 * ポリラインタイルの画素。tile-sw.js の「Shape tiles」節のポリラインと同じ描き方であることを固定する。
 * PolygonTileRendererDeviceTest と同じく、1 単位 = 1 画素（pixelScale = 1）で描かせる。
 *
 * z0 のタイルは世界全体が 256 画素。経度 λ は x = (λ + 180) / 360 * 256、赤道は y = 128。
 * js-sdk-core の `PolylineTileRendering.test.mjs`、ios-sdk の `PolylineTileRendererTests` と 1 対 1。
 */
@RunWith(AndroidJUnit4::class)
class PolylineTileRendererDeviceTest {
    private val red = Color(0xFFFF0000)
    private val blue = Color(0xFF0000FF)
    private val green = Color(0xFF00FF00)

    private fun p(
        latitude: Double,
        longitude: Double,
    ): GeoPointInterface = GeoPoint.fromLatLong(latitude, longitude)

    private fun renderer(vararg states: PolylineState) =
        ShapeTileRenderer(tileSize = 256, pixelScale = { 1f }, painter = PolylineTilePainter).apply {
            setItems(states.map(TilePolyline::from))
        }

    private fun alpha(pixel: Int) = pixel ushr 24

    private fun rgb(pixel: Int) = pixel and 0xFFFFFF

    private fun centre(bitmap: Bitmap) = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)

    /** The rows of column [x] that are painted (alpha over half), top to bottom. */
    private fun paintedRows(
        bitmap: Bitmap,
        x: Int,
    ): List<Int> = (0 until bitmap.height).filter { alpha(bitmap.getPixel(x, it)) > 0x80 }

    /** World y (in pixels of a 256 * 2^z world) of [lat]. */
    private fun worldY(
        lat: Double,
        z: Int,
    ): Double {
        val s = sin(lat * PI / 180)
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * 256.0 * (1 shl z)
    }

    /** 水平線は期待した行に、線幅ぶんの太さで出る */
    @Test
    fun horizontalLineIsOnItsRowAsWideAsItsStroke() {
        val bitmap =
            renderer(
                PolylineState(points = listOf(p(0.0, -90.0), p(0.0, 90.0)), strokeColor = red, strokeWidth = 4.dp),
            ).renderBitmap(0, 0, 0)!!
        // 赤道は y = 128。幅 4 なら 126..129
        assertEquals(listOf(126, 127, 128, 129), paintedRows(bitmap, 128))
        assertEquals(0xFF0000, rgb(bitmap.getPixel(128, 127)))
        // 端は丸く、線幅の半分だけ先まで: 経度 -90 度は x = 64、その 2 画素手前まで
        assertTrue(alpha(bitmap.getPixel(63, 128)) > 0x80)
        assertEquals(0, alpha(bitmap.getPixel(60, 128)))
        // 塗らない（線の外は透明）
        assertEquals(0, alpha(bitmap.getPixel(128, 100)))
    }

    /** タイルの境目にある線は、両側のタイルに同じ行・太さで切れ目なく出る */
    @Test
    fun lineOnATileEdgeIsDrawnOnBothSidesWithoutAGap() {
        // z1: 赤道は y 方向のタイルの境目（世界 512 画素の y = 256）
        val r =
            renderer(
                PolylineState(points = listOf(p(0.0, -90.0), p(0.0, 90.0)), strokeColor = red, strokeWidth = 4.dp),
            )
        val north = r.renderBitmap(0, 0, 1)!!
        val south = r.renderBitmap(0, 1, 1)!!
        // 経度 -45 度は z1 の x = 192
        assertEquals("北のタイルの下 2 行", listOf(254, 255), paintedRows(north, 192))
        assertEquals("南のタイルの上 2 行", listOf(0, 1), paintedRows(south, 192))
        // 東西のタイルの境目（経度 0 度）を横切っても、両側の端の列で同じ行
        val east = r.renderBitmap(1, 0, 1)!!
        assertEquals(paintedRows(north, 255), paintedRows(east, 0))
        assertEquals(listOf(254, 255), paintedRows(east, 0))
    }

    /** 斜めの線もタイルの境目で切れない: 境目の両側の端の列で、塗った行がつながる */
    @Test
    fun slantedLineContinuesAcrossTheTileEdge() {
        val r =
            renderer(
                PolylineState(points = listOf(p(30.0, -20.0), p(10.0, 20.0)), strokeColor = blue, strokeWidth = 3.dp),
            )
        // z2: 経度 0 度は x 方向のタイル 1 と 2 の境目
        val y = (worldY(20.0, 2) / 256).toInt()
        val west = r.renderBitmap(1, y, 2)!!
        val east = r.renderBitmap(2, y, 2)!!
        val westRows = paintedRows(west, 255)
        val eastRows = paintedRows(east, 0)
        assertTrue(westRows.isNotEmpty() && eastRows.isNotEmpty())
        // 1 列ぶん右へ下がる線なので、重なるか 1 行ずれるだけ
        assertTrue(
            "$westRows / $eastRows",
            westRows.first() <= eastRows.last() && eastRows.first() <= westRows.last() + 1,
        )
    }

    /** ズームを変えても線幅（画素）は同じ */
    @Test
    fun strokeKeepsItsWidthAcrossZooms() {
        val r =
            renderer(
                PolylineState(points = listOf(p(20.0, -170.0), p(20.0, 170.0)), strokeColor = blue, strokeWidth = 4.dp),
            )
        val widths =
            (2..5).map { z ->
                val y = (worldY(20.0, z) / 256).toInt()
                val bitmap = r.renderBitmap(1, y, z)!!
                paintedRows(bitmap, 128).size
            }
        assertEquals(listOf(4, 4, 4, 4), widths)
    }

    /** 1 画素に満たない線は線幅の丸（点）になる */
    @Test
    fun subPixelLineIsADotAsWideAsTheLine() {
        val bitmap =
            renderer(
                PolylineState(points = listOf(p(0.0, 0.0), p(0.0, 0.001)), strokeColor = green, strokeWidth = 4.dp),
            ).renderBitmap(0, 0, 0)!!
        var painted = 0
        for (py in 120..136) {
            for (px in 120..136) {
                val pixel = bitmap.getPixel(px, py)
                if (alpha(pixel) > 0x80) {
                    painted++
                    assertEquals(0x00FF00, rgb(pixel))
                }
            }
        }
        // 直径 4 の丸: 12 画素前後
        assertTrue("painted=$painted", painted in 8..16)
    }

    /** zIndex の大きい線が上、同じなら後に渡した線が上 */
    @Test
    fun higherZIndexIsOnTop_thenLaterInTheList() =
        runBlocking {
            val set = PolylineTileSet()
            try {
                val over =
                    PolylineState(points = listOf(p(-10.0, 0.0), p(10.0, 0.0)), strokeColor = red, strokeWidth = 6.dp, zIndex = 1)
                val under =
                    PolylineState(points = listOf(p(0.0, -10.0), p(0.0, 10.0)), strokeColor = blue, strokeWidth = 6.dp, zIndex = 0)
                set.setPolylines(listOf(over, under))
                // The set draws at the device's density: the crossing is the bitmap's centre.
                assertEquals("zIndex 1 が上", 0xFF0000, rgb(centre(set.renderer.renderBitmap(0, 0, 0)!!)))

                val first =
                    PolylineState(points = listOf(p(-10.0, 0.0), p(10.0, 0.0)), strokeColor = red, strokeWidth = 6.dp)
                val second =
                    PolylineState(points = listOf(p(0.0, -10.0), p(0.0, 10.0)), strokeColor = green, strokeWidth = 6.dp)
                set.setPolylines(listOf(first, second))
                assertEquals("後に渡した線が上", 0x00FF00, rgb(centre(set.renderer.renderBitmap(0, 0, 0)!!)))
            } finally {
                set.dispose()
            }
        }

    /** 透明な線・幅 0 の線は描かない。何も無いタイルは null（空） */
    @Test
    fun invisibleLinesDrawNothing_andEmptyTilesAreNull() {
        val r =
            renderer(
                PolylineState(
                    points = listOf(p(10.0, 10.0), p(20.0, 20.0)), strokeColor = Color.Transparent,
                    strokeWidth =
                        4
                            .dp,
                ),
                PolylineState(points = listOf(p(10.0, 10.0), p(20.0, 30.0)), strokeColor = red, strokeWidth = 0.dp),
            )
        // 北東のタイルにはあるが、見えないものだけ
        assertNull(r.renderTile(TileRequest(x = 1, y = 0, z = 1)))
        // 北西のタイルには何も無い
        assertNull(r.renderTile(TileRequest(x = 0, y = 0, z = 1)))
    }

    /** 色を変えたら、その線のタイルだけ描き直す（他はキャッシュ） */
    @Test
    fun inPlaceChangeRedrawsOnlyTheTilesItTouches() {
        val west =
            PolylineState(
                points = listOf(p(10.0, -100.0), p(20.0, -90.0)), strokeColor = blue, id = "west",
                strokeWidth =
                    3
                        .dp,
            )
        val east =
            PolylineState(
                points = listOf(p(10.0, 90.0), p(20.0, 100.0)), strokeColor = blue, id = "east",
                strokeWidth =
                    3
                        .dp,
            )
        val r = renderer(west, east)
        val westTile = TileRequest(x = 0, y = 0, z = 1)
        val eastTile = TileRequest(x = 1, y = 0, z = 1)
        val westBefore = r.renderTile(westTile)!!
        val eastBefore = r.renderTile(eastTile)!!

        west.strokeColor = green
        r.update(listOf(0 to TilePolyline.from(west)))

        val westAfter = r.renderTile(westTile)!!
        assertNotSame("変えた線のタイルは描き直す", westBefore, westAfter)
        assertSame("触れていないタイルはキャッシュから返す", eastBefore, r.renderTile(eastTile))
        val decoded = BitmapFactory.decodeByteArray(westAfter, 0, westAfter.size)
        // 線の中点（経度 -95 度、北緯 ~15 度）は z1 で x = 120
        val y = worldY(15.0, 1).toInt()
        val mid = (y - 3..y + 3).map { decoded.getPixel(120, it) }.firstOrNull { alpha(it) > 0x80 }
        assertNotNull(mid)
        assertEquals(0x00FF00, rgb(mid!!))
    }

    /** 外した線（null）は、重なっていたタイルだけ描き直して消える */
    @Test
    fun removedLineRedrawsOnlyItsTiles() {
        val west = PolylineState(points = listOf(p(10.0, -100.0), p(20.0, -90.0)), strokeColor = blue, id = "west")
        val east = PolylineState(points = listOf(p(10.0, 90.0), p(20.0, 100.0)), strokeColor = blue, id = "east")
        val r = renderer(west, east)
        assertNotNull(r.renderTile(TileRequest(x = 0, y = 0, z = 1)))
        val eastBefore = r.renderTile(TileRequest(x = 1, y = 0, z = 1))!!
        r.update(listOf(0 to null))
        assertNull(r.renderTile(TileRequest(x = 0, y = 0, z = 1)))
        assertSame(eastBefore, r.renderTile(TileRequest(x = 1, y = 0, z = 1)))
    }

    /** 太い線の色を変えたら、線幅ぶん隣のタイルにはみ出した所も描き直す */
    @Test
    fun aChangeRedrawsTheNeighbourTheStrokeReachesInto() {
        // z1 の北のタイルの下端に沿う太い線: 南のタイルの上端にもはみ出す
        val line =
            PolylineState(points = listOf(p(0.5, -90.0), p(0.5, -10.0)), strokeColor = blue, strokeWidth = 10.dp, id = "l")
        val r = renderer(line)
        val south = TileRequest(x = 0, y = 1, z = 1)
        val before = r.renderTile(south)!!
        line.strokeColor = red
        r.update(listOf(0 to TilePolyline.from(line)))
        val after = r.renderTile(south)!!
        assertNotSame(before, after)
        val decoded = BitmapFactory.decodeByteArray(after, 0, after.size)
        assertEquals(0xFF0000, rgb(decoded.getPixel(192, 0)))
    }

    /** 取りやめられたタイルは空ではなく失敗にする */
    @Test
    fun cancelledTileFailsInsteadOfComingBackEmpty() {
        val r = renderer(PolylineState(points = listOf(p(0.0, 0.0), p(10.0, 10.0)), strokeColor = blue))
        val thrown = runCatching { r.renderTile(TileRequest(x = 0, y = 0, z = 0)) { true } }.exceptionOrNull()
        assertTrue(thrown is ShapeTileRenderer.TileRenderCancelled)
    }
}
