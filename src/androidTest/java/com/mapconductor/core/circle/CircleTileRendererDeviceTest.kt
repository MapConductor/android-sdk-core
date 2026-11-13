package com.mapconductor.core.circle

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.geometry.circleToRing
import com.mapconductor.core.polygon.toMercatorRing
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
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.runBlocking

/**
 * 円のタイルの画素。tile-sw.js の「Shape tiles」節の円と同じ描き方であることを固定する。
 * PolylineTileRendererDeviceTest と同じく、1 単位 = 1 画素（pixelScale = 1）で描かせる。
 *
 * z0 のタイルは世界全体が 256 画素。js-sdk-core の `CircleTileRendering.test.mjs`、
 * ios-sdk の `CircleTileRendererTests` と 1 対 1。
 */
@RunWith(AndroidJUnit4::class)
class CircleTileRendererDeviceTest {
    @Test
    fun filteringSharesGeometryButHidesSourcesAndPixels() = runBlocking {
        val left = CircleState(id = "left", center = p(0.0, -60.0), radiusMeters = 500000.0, zIndex = 0)
        val right = CircleState(id = "right", center = p(0.0, 60.0), radiusMeters = 500000.0, zIndex = 1)
        val set = CircleTileSet()
        try {
            set.setCircles(listOf(left, right))
            val leftGeometry = set.renderer.itemAt(0)
            val rightGeometry = set.renderer.itemAt(1)
            val initialIds = set.shapes.ids()
            repeat(3) {
                set.setCircles(listOf(left))
                assertSame(leftGeometry, set.renderer.itemAt(0))
                assertSame(rightGeometry, set.renderer.itemAt(1))
                assertEquals(setOf("left"), set.shapes.ids())
                assertNull(set.shapes.source("right"))
                val sources = mutableListOf<CircleState>()
                set.shapes.visitSources { sources.add(it) }
                assertEquals(listOf(left), sources)
                val bitmap = set.renderer.renderBitmap(0, 0, 0)!!
                assertTrue(alpha(bitmap.getPixel(bitmap.width / 3, bitmap.height / 2)) > 0)
                assertEquals(0, alpha(bitmap.getPixel(bitmap.width * 2 / 3, bitmap.height / 2)))
                bitmap.recycle()
                set.setCircles(listOf(left, right))
                assertSame(rightGeometry, set.renderer.itemAt(1))
                assertSame(right, set.shapes.source("right"))
                assertEquals(setOf("left", "right"), initialIds)
            }
            // Same state identity with a changed value must rebuild geometry.
            left.radiusMeters = 700000.0
            set.setCircles(listOf(left, right))
            assertNotSame(leftGeometry, set.renderer.itemAt(0))
        } finally {
            set.dispose()
        }
    }

    @Test
    fun filteringDoesNotReuseChangedStackingOrder() = runBlocking {
        val a = CircleState(id = "a", center = p(0.0, 0.0), radiusMeters = 1000.0, zIndex = 0)
        val b = CircleState(id = "b", center = p(0.0, 0.0), radiusMeters = 1000.0, zIndex = 0)
        val set = CircleTileSet()
        try {
            set.setCircles(listOf(a, b))
            val first = set.renderer.itemAt(0)
            set.setCircles(listOf(b, a))
            assertNotSame(first, set.renderer.itemAt(0))
            b.zIndex = 2
            set.setCircles(listOf(b, a))
            assertEquals(1000.0, set.renderer.itemAt(0)!!.radiusMeters, 0.0)
            assertEquals(listOf("a", "b"), set.shapes.ids().sorted())
        } finally {
            set.dispose()
        }
    }

    private val red = Color(0xFFFF0000)
    private val blue = Color(0xFF0000FF)
    private val green = Color(0xFF00FF00)

    private fun p(
        latitude: Double,
        longitude: Double,
    ): GeoPointInterface = GeoPoint.fromLatLong(latitude, longitude)

    private fun renderer(vararg states: CircleState) =
        ShapeTileRenderer(tileSize = 256, pixelScale = { 1f }, painter = CircleTilePainter).apply {
            setItems(states.sortedBy(::effectiveZIndex).map(TileCircle::from))
        }

    private fun alpha(pixel: Int) = pixel ushr 24

    private fun rgb(pixel: Int) = pixel and 0xFFFFFF

    private fun worldX(
        lng: Double,
        z: Int,
    ): Double = (lng + 180) / 360 * 256.0 * (1 shl z)

    private fun worldY(
        lat: Double,
        z: Int,
    ): Double {
        val s = sin(lat * PI / 180)
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * 256.0 * (1 shl z)
    }

    /** The columns of row [y] that are painted (alpha over half). */
    private fun paintedColumns(
        bitmap: Bitmap,
        y: Int,
    ): List<Int> = (0 until bitmap.width).filter { alpha(bitmap.getPixel(it, y)) > 0x80 }

    /** A circle at the equator, [radiusPx] pixels across at [z] (radius of a z-world pixel = 40075 km / 256 / 2^z). */
    private fun equatorCircle(
        lng: Double,
        radiusPx: Double,
        z: Int,
        fill: Color = Color(0x800000FF),
        stroke: Color = red,
        strokeWidth: Float = 2f,
        id: String? = null,
        zIndex: Int? = 0,
        lat: Double = 0.0,
    ): CircleState {
        val metersPerPx = 2 * PI * 6378137.0 / (256.0 * (1 shl z))
        return CircleState(
            center = p(lat, lng),
            radiusMeters = radiusPx * metersPerPx,
            fillColor = fill,
            strokeColor = stroke,
            strokeWidth = strokeWidth.dp,
            id = id,
            zIndex = zIndex,
        )
    }

    /** 中心は塗りの色、半径の所に縁の色 */
    @Test
    fun tiledManagerSharesSourceStatesWithoutPersistentEntities() = kotlinx.coroutines.runBlocking {
        val states = List(2001) { i -> CircleState(center = GeoPoint(0.001 * i, 0.0), radiusMeters = 5.0, id = "shared-$i") }
        val tiler = CircleTiler()
        val manager = CircleManager<Unit>()
        try {
            tiler.route(states)
            assertTrue(manager.setTiledSource(tiler))
            assertTrue(manager.nativeEntities().isEmpty())
            assertTrue(manager.hasEntity("shared-123"))
            org.junit.Assert.assertSame(states[123], manager.getEntity("shared-123")!!.state)
            org.junit.Assert.assertSame(states[123], manager.find(states[123].center)!!.state)
            assertTrue(manager.nativeEntities().isEmpty())
            val replacement = states[25].copy(radiusMeters = 8.0)
            tiler.update(replacement)
            org.junit.Assert.assertSame(replacement, manager.getEntity(replacement.id)!!.state)
            tiler.remove("shared-123")
            org.junit.Assert.assertFalse(manager.hasEntity("shared-123"))
            assertNull(manager.getEntity("shared-123"))
            manager.clear()
            org.junit.Assert.assertFalse(manager.hasEntity("shared-25"))
        } finally { tiler.dispose() }
    }

    @Test
    fun subpixelOverlapKeepsOnlyTopCircle_andZoomRestoresBoth() {
        val lower = equatorCircle(0.0, 0.2, 0, fill = Color(0x800000FF), id = "lower")
        val upper = equatorCircle(0.0, 0.2, 0, fill = Color(0x80FF0000), id = "upper")
        val r = renderer(lower, upper)
        val small = r.renderBitmap(0, 0, 0)!!
        assertEquals(0, rgb(small.getPixel(128, 128)) and 0xFF)
        assertEquals(128, alpha(small.getPixel(128, 128)))
        val large = r.renderBitmap(8, 8, 4)!!
        assertTrue("both translucent circles return", (large.getPixel(0, 0) and 0xFF) > 0)
        assertTrue(alpha(large.getPixel(0, 0)) > 128)
    }

    @Test
    fun centreIsFilled_andTheEdgeIsStroked() {
        // z4: 世界 4096 px。経度 -168.75 度 = x 128（タイル 0 の真ん中）、北緯 10 度 = y 1933.6（タイル 7）
        val lat = 10.0
        val z = 4
        val state = equatorCircle(lng = -168.75, radiusPx = 40.0, z = z, fill = Color(0xFF0000FF), lat = lat)
        val bitmap = renderer(state).renderBitmap(0, 7, z)!!
        val cx = worldX(-168.75, z) - 0
        val cy = worldY(lat, z) - 7 * 256
        assertEquals("中心は塗り", 0x0000FF, rgb(bitmap.getPixel(cx.toInt(), cy.toInt())))
        // 赤道の 40 px は緯度 10 度で 40 / cos(10°) ≒ 40.6 px（メルカトルで地上の大きさ）
        val radius = 40.0 / kotlin.math.cos(lat * PI / 180)
        val row = cy.toInt()
        val painted = paintedColumns(bitmap, row)
        assertEquals("左端", cx - radius - 1, painted.first().toDouble(), 1.5)
        assertEquals("右端", cx + radius + 1, painted.last().toDouble() + 1, 1.5)
        assertEquals("縁は赤", 0xFF0000, rgb(bitmap.getPixel((cx + radius).toInt(), row)))
        assertEquals("外は透明", 0, alpha(bitmap.getPixel((cx + radius + 4).toInt(), row)))
    }

    /** タイルの境目にかかる円は、両側のタイルに同じ位置・大きさで欠けずに出る */
    @Test
    fun circleOnATileEdgeIsDrawnOnBothSides() {
        // z1: 経度 0 度は x 方向のタイルの境目。中心をそこに置く
        val z = 1
        val state = equatorCircle(lng = 0.0, radiusPx = 30.0, z = z, fill = Color(0xFF00FF00), stroke = green, lat = 30.0)
        val r = renderer(state)
        val west = r.renderBitmap(0, 0, z)!!
        val east = r.renderBitmap(1, 0, z)!!
        val row = worldY(30.0, z).toInt()
        val westCols = paintedColumns(west, row)
        val eastCols = paintedColumns(east, row)
        // 西のタイルは右端まで、東のタイルは左端から塗る。幅は左右で同じ
        assertEquals(255, westCols.last())
        assertEquals(0, eastCols.first())
        assertEquals((256 - westCols.first()).toDouble(), (eastCols.last() + 1).toDouble(), 1.0)
        // 中心の行で切れ目が無い（境目の両側の画素が塗られている）
        assertTrue(alpha(west.getPixel(255, row)) > 0x80 && alpha(east.getPixel(0, row)) > 0x80)
    }

    /** ズームを 1 段上げると半径の画素数は 2 倍（地上の大きさが保たれる）、縁幅は同じ */
    @Test
    fun radiusDoublesPerZoom_strokeStaysTheSame() {
        // ボストンの、z17 でタイルの 1/3 の所: z18 で 2/3、z19 で 1/3。どのズームでも円がタイルに収まる
        val scale17 = 256.0 * (1 shl 17)
        val wx17 = (floor(worldX(-71.0605, 17) / 256) + 1.0 / 3) * 256
        val wy17 = (floor(worldY(42.3555, 17) / 256) + 1.0 / 3) * 256
        val lng = wx17 / scale17 * 360 - 180
        val lat = kotlin.math.atan(kotlin.math.sinh(PI * (1 - 2 * wy17 / scale17))) * 180 / PI
        val state =
            CircleState(
                center = p(lat, lng),
                radiusMeters = 15.0,
                fillColor = Color.Transparent,
                strokeColor = blue,
                strokeWidth = 2.dp,
            )
        val r = renderer(state)
        val widths = mutableListOf<Int>()
        val strokes = mutableListOf<Int>()
        for (z in 17..19) {
            val wx = worldX(lng, z)
            val wy = worldY(lat, z)
            val tx = (wx / 256).toInt()
            val ty = (wy / 256).toInt()
            val bitmap = r.renderBitmap(tx, ty, z)
            assertNotNull("z$z", bitmap)
            val row = (wy - ty * 256).toInt()
            val cols = paintedColumns(bitmap!!, row)
            widths += cols.last() - cols.first()
            // 縁幅: 左の縁の塗られた列の数
            var n = 0
            var c = cols.first()
            while (c < 256 && alpha(bitmap.getPixel(c, row)) > 0x80) {
                n++
                c++
            }
            strokes += n
        }
        assertEquals("$widths", widths[0] * 2.0, widths[1].toDouble(), 2.5)
        assertEquals("$widths", widths[1] * 2.0, widths[2].toDouble(), 2.5)
        assertTrue("$strokes", strokes.all { it in 1..3 })
    }

    /** 直径 1 px 未満は 1 px の点（アンチエイリアスなし、塗りの色） */
    @Test
    fun subPixelCircleIsOnePixel() {
        val state = CircleState(center = p(0.0, 0.0), radiusMeters = 100.0, fillColor = Color(0xFF00FF00), strokeColor = red)
        val bitmap = renderer(state).renderBitmap(0, 0, 0)!!
        var painted = 0
        for (py in 120..136) {
            for (px in 120..136) {
                val pixel = bitmap.getPixel(px, py)
                if (alpha(pixel) > 0) {
                    painted++
                    assertEquals(0x00FF00, rgb(pixel))
                    assertEquals(0xFF, alpha(pixel))
                }
            }
        }
        assertEquals(1, painted)
        assertTrue(alpha(bitmap.getPixel(128, 128)) == 0xFF)
    }

    /** 直径 4 px 未満は縁を描かない（塗りだけ） */
    @Test
    fun underFourPixelsAcrossHasNoOutline() {
        val state = equatorCircle(lng = 0.0, radiusPx = 1.5, z = 0, fill = Color(0xFF00FF00), stroke = red, strokeWidth = 2f)
        val bitmap = renderer(state).renderBitmap(0, 0, 0)!!
        for (py in 120..136) {
            for (px in 120..136) {
                val pixel = bitmap.getPixel(px, py)
                if (alpha(pixel) > 0) assertTrue("($px, $py) ${Integer.toHexString(pixel)}", (pixel and 0xFF0000) ushr 16 < 0x40)
            }
        }
    }

    /** 高緯度（70 度、200 km）は 128 角形で、circleToRing の点が通る画素に縁がある */
    @Test
    fun highLatitudeCircleIsTheNativeRing() {
        val state =
            CircleState(
                center = p(70.0, 20.0),
                radiusMeters = 200_000.0,
                fillColor = Color.Transparent,
                strokeColor = red,
                strokeWidth = 3.dp,
            )
        val z = 5
        val r = renderer(state)
        val ring = toMercatorRing(circleToRing(state.center, 200_000.0, true, 128))
        val scale = 256.0 * (1 shl z)
        var checked = 0
        for (i in ring.indices step 2 * 8) {
            val wx = ring[i] * scale
            val wy = ring[i + 1] * scale
            val tx = (wx / 256).toInt()
            val ty = (wy / 256).toInt()
            val bitmap = r.renderBitmap(tx, ty, z) ?: continue
            val px = (wx - tx * 256).toInt().coerceIn(0, 255)
            val py = (wy - ty * 256).toInt().coerceIn(0, 255)
            // 点のまわり 1 画素に縁
            val near =
                (-1..1).any { dy ->
                    (-1..1).any { dx ->
                        val x = (px + dx).coerceIn(0, 255)
                        val y = (py + dy).coerceIn(0, 255)
                        alpha(bitmap.getPixel(x, y)) > 0x80 && rgb(bitmap.getPixel(x, y)) == 0xFF0000
                    }
                }
            assertTrue("点 $i ($wx, $wy)", near)
            checked++
        }
        assertEquals(16, checked)
    }

    /** zIndex の大きい円が上、未指定は中心からの自動値（南が上） */
    @Test
    fun higherZIndexIsOnTop_andUnsetIsTheAutomaticOne() =
        runBlocking {
            val set = CircleTileSet()
            try {
                val over = equatorCircle(lng = 0.0, radiusPx = 20.0, z = 0, fill = red, stroke = red, zIndex = 1)
                val under = equatorCircle(lng = 0.0, radiusPx = 20.0, z = 0, fill = blue, stroke = blue, zIndex = 0)
                set.setCircles(listOf(over, under))
                val bitmap = set.renderer.renderBitmap(0, 0, 0)!!
                assertEquals("zIndex 1 が上", 0xFF0000, rgb(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)))

                // 未指定: 南の円（自動値が大きい）が北の円の上
                val south = equatorCircle(lng = 0.0, radiusPx = 20.0, z = 0, fill = green, stroke = green, zIndex = null, lat = -1.0)
                val north = equatorCircle(lng = 0.0, radiusPx = 20.0, z = 0, fill = red, stroke = red, zIndex = null, lat = 1.0)
                set.setCircles(listOf(south, north))
                val auto = set.renderer.renderBitmap(0, 0, 0)!!
                assertEquals("南が上", 0x00FF00, rgb(auto.getPixel(auto.width / 2, auto.height / 2)))
            } finally {
                set.dispose()
            }
        }

    /** 透明な円は描かない。何も無いタイルは null（空） */
    @Test
    fun invisibleCirclesDrawNothing_andEmptyTilesAreNull() {
        val r =
            renderer(
                CircleState(center = p(40.0, 90.0), radiusMeters = 500_000.0, fillColor = Color.Transparent, strokeColor = Color.Transparent),
                CircleState(center = p(40.0, 90.0), radiusMeters = 500_000.0, fillColor = Color.Transparent, strokeWidth = 0.dp),
            )
        assertNull(r.renderTile(TileRequest(x = 1, y = 0, z = 1)))
        assertNull(r.renderTile(TileRequest(x = 0, y = 0, z = 1)))
    }

    /** 色を変えたら、その円のタイルだけ描き直す（他はキャッシュ） */
    @Test
    fun inPlaceChangeRedrawsOnlyTheTilesItTouches() {
        val west = CircleState(center = p(30.0, -90.0), radiusMeters = 300_000.0, fillColor = blue, strokeColor = blue, id = "west")
        val east = CircleState(center = p(30.0, 90.0), radiusMeters = 300_000.0, fillColor = blue, strokeColor = blue, id = "east")
        val r = renderer(west, east)
        val westTile = TileRequest(x = 0, y = 0, z = 1)
        val eastTile = TileRequest(x = 1, y = 0, z = 1)
        val westBefore = r.renderTile(westTile)!!
        val eastBefore = r.renderTile(eastTile)!!

        // renderer() は実効 zIndex で並べる: 自動値は同じ緯度なら西が上（大きい）→ west は後ろ
        val westIndex = listOf(west, east).sortedBy(::effectiveZIndex).indexOf(west)
        west.fillColor = green
        west.strokeColor = green
        r.update(listOf(westIndex to TileCircle.from(west)))

        val westAfter = r.renderTile(westTile)!!
        assertNotSame("変えた円のタイルは描き直す", westBefore, westAfter)
        assertSame("触れていないタイルはキャッシュから返す", eastBefore, r.renderTile(eastTile))
        val decoded = BitmapFactory.decodeByteArray(westAfter, 0, westAfter.size)
        assertEquals(0x00FF00, rgb(decoded.getPixel(worldX(-90.0, 1).toInt(), worldY(30.0, 1).toInt())))
    }

    /** 取りやめられたタイルは空ではなく失敗にする */
    @Test
    fun cancelledTileFailsInsteadOfComingBackEmpty() {
        val r = renderer(CircleState(center = p(0.0, 0.0), radiusMeters = 1_000_000.0, fillColor = blue))
        val thrown = runCatching { r.renderTile(TileRequest(x = 0, y = 0, z = 0)) { true } }.exceptionOrNull()
        assertTrue(thrown is ShapeTileRenderer.TileRenderCancelled)
    }
}
