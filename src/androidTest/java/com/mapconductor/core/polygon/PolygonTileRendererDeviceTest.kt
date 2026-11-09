package com.mapconductor.core.polygon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.tileserver.TileRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ポリゴンタイルの画素。tile-sw.js の「Polygon tiles」節と同じ描き方であることを固定する。
 *
 * 1 単位 = 1 画素（pixelScale = 1）で描かせ、z0 のタイル（世界全体が 256 画素）を見る。
 * 経度 λ は x = (λ + 180) / 360 * 256、赤道は y = 128。
 */
@RunWith(AndroidJUnit4::class)
class PolygonTileRendererDeviceTest {
    private val red = Color(0xFFFF0000)
    private val blue = Color(0xFF0000FF)
    private val green = Color(0xFF00FF00)

    private fun p(
        latitude: Double,
        longitude: Double,
    ): GeoPointInterface = GeoPoint.fromLatLong(latitude, longitude)

    private fun box(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): List<GeoPointInterface> = listOf(p(south, west), p(south, east), p(north, east), p(north, west))

    private fun renderer(vararg states: PolygonState) =
        PolygonTileRenderer(tileSize = 256, pixelScale = { 1f }).apply {
            setPolygons(states.map(TilePolygon::from))
        }

    private fun x(lng: Double) = ((lng + 180) / 360 * 256).toInt()

    private fun alpha(pixel: Int) = pixel ushr 24

    private fun rgb(pixel: Int) = pixel and 0xFFFFFF

    /** 外周と同じ向きの穴も抜け、枠線は外周にだけ引く */
    @Test
    fun holeWoundLikeOutlineIsCutOut_andOnlyOutlineIsStroked() {
        val outer = box(-60.0, -90.0, 60.0, 90.0)
        val hole = box(-20.0, -30.0, 20.0, 30.0)
        // 穴を外周と同じ向きで渡す。nonzero で抜けるのは、描く前に巻きを逆にそろえるから
        val bitmap =
            renderer(
                PolygonState(points = outer, holes = listOf(hole), fillColor = blue, strokeColor = red, strokeWidth = 2.dp),
            ).renderBitmap(0, 0, 0)!!
        assertEquals("穴の中は透明", 0, alpha(bitmap.getPixel(128, 128)))
        assertEquals("塗り", 0x0000FF, rgb(bitmap.getPixel(x(-60.0), 128)))
        assertEquals("外周の枠線", 0xFF0000, rgb(bitmap.getPixel(x(-90.0), 128)))
        // 穴の縁（経度 -30 度）は塗りの縁で、枠線の色は無い
        for (dx in -1..1) {
            val pixel = bitmap.getPixel(x(-30.0) + dx, 128)
            assertTrue("穴の縁 dx=$dx に枠線の赤: ${Integer.toHexString(pixel)}", (pixel shr 16 and 0xFF) < 0x40)
        }
    }

    /** 画面上 4 画素未満のポリゴンは枠線を省く */
    @Test
    fun polygonUnderFourPixelsHasNoOutline() {
        // 経度 2 度 = 1.4 画素、緯度 2 度 ≒ 1.4 画素
        val bitmap =
            renderer(
                PolygonState(points = box(0.0, 0.0, 4.0, 4.0), fillColor = blue, strokeColor = red, strokeWidth = 1.dp),
            ).renderBitmap(0, 0, 0)!!
        var reddest = 0
        for (py in 120..130) for (px in 125..135) reddest = maxOf(reddest, bitmap.getPixel(px, py) shr 16 and 0xFF)
        assertEquals("枠線の赤が無い", 0, reddest)
    }

    /** 1 画素に満たないポリゴンは塗りの色の 1 画素になる */
    @Test
    fun subPixelPolygonIsOnePixelOfFill() {
        val bitmap =
            renderer(
                PolygonState(points = box(0.0, 0.0, 0.01, 0.01), fillColor = blue, strokeColor = red),
            ).renderBitmap(0, 0, 0)!!
        var painted = 0
        for (py in 0 until 256) {
            for (px in 0 until 256) {
                val pixel = bitmap.getPixel(px, py)
                if (alpha(pixel) != 0) {
                    painted++
                    assertEquals(0x0000FF, rgb(pixel))
                }
            }
        }
        assertEquals(1, painted)
    }

    /** 何も無いタイルと、見えない色だけのタイルは null（空） */
    @Test
    fun tileWithNothingVisibleIsEmpty() {
        val r =
            renderer(
                PolygonState(points = box(0.0, 10.0, 10.0, 20.0), fillColor = Color.Transparent, strokeColor = Color.Transparent),
            )
        // z1 の北西のタイル（経度 -180..0、北半球）には何も無い
        assertNull(r.renderTile(TileRequest(x = 0, y = 0, z = 1)))
        // 北東のタイルにはあるが、見えない色だけ
        assertNull(r.renderTile(TileRequest(x = 1, y = 0, z = 1)))
    }

    /** その場の変更は、重なるタイルだけ描き直す */
    @Test
    fun inPlaceChangeRedrawsOnlyOverlappingTiles() {
        val west = PolygonState(points = box(10.0, -100.0, 20.0, -90.0), fillColor = blue, id = "west")
        val east = PolygonState(points = box(10.0, 90.0, 20.0, 100.0), fillColor = blue, id = "east")
        val r = renderer(west, east)
        val westTile = TileRequest(x = 0, y = 0, z = 1)
        val eastTile = TileRequest(x = 1, y = 0, z = 1)
        val westBefore = r.renderTile(westTile)!!
        val eastBefore = r.renderTile(eastTile)!!

        west.fillColor = green
        r.update(listOf(0 to TilePolygon.from(west)))

        val westAfter = r.renderTile(westTile)!!
        assertNotSame("変えたポリゴンのタイルは描き直す", westBefore, westAfter)
        assertSame("触れていないタイルはキャッシュから返す", eastBefore, r.renderTile(eastTile))
        val decoded = decode(westAfter)
        // z1: 世界が 512 画素。経度 -95 度は x = 120、北緯 15 度は y = 234
        assertEquals(0x00FF00, rgb(decoded.getPixel(120, 234)))
    }

    /** タイルから外したポリゴン（null）は、重なっていたタイルだけ描き直して消える */
    @Test
    fun removedPolygonRedrawsOnlyItsTiles() {
        val west = PolygonState(points = box(10.0, -100.0, 20.0, -90.0), fillColor = blue, id = "west")
        val east = PolygonState(points = box(10.0, 90.0, 20.0, 100.0), fillColor = blue, id = "east")
        val r = renderer(west, east)
        val westTile = TileRequest(x = 0, y = 0, z = 1)
        val eastTile = TileRequest(x = 1, y = 0, z = 1)
        assertNotNull(r.renderTile(westTile))
        val eastBefore = r.renderTile(eastTile)!!

        r.update(listOf(0 to null))

        assertNull("外したポリゴンしか無いタイルは空", r.renderTile(westTile))
        assertSame("触れていないタイルはキャッシュから返す", eastBefore, r.renderTile(eastTile))
    }

    /** 形が変わったら索引も作り直す */
    @Test
    fun movedShapeIsReindexed() {
        val moving = PolygonState(points = box(10.0, -100.0, 20.0, -90.0), fillColor = blue, id = "m")
        val r = renderer(moving)
        assertNull(r.renderTile(TileRequest(x = 1, y = 0, z = 1)))
        moving.points = box(10.0, 90.0, 20.0, 100.0)
        r.update(listOf(0 to TilePolygon.from(moving)))
        assertNotNull("移った先のタイルに出る", r.renderTile(TileRequest(x = 1, y = 0, z = 1)))
        assertNull("元のタイルからは消える", r.renderTile(TileRequest(x = 0, y = 0, z = 1)))
    }

    /** 取りやめられたタイルは空ではなく失敗にする */
    @Test
    fun cancelledTileFailsInsteadOfComingBackEmpty() {
        val r = renderer(PolygonState(points = box(0.0, 0.0, 10.0, 10.0), fillColor = blue))
        val thrown =
            runCatching { r.renderTile(TileRequest(x = 0, y = 0, z = 0)) { true } }.exceptionOrNull()
        assertTrue(thrown is PolygonTileRenderer.TileRenderCancelled)
    }

    private fun decode(png: ByteArray): Bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
}
