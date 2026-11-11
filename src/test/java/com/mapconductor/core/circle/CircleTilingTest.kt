package com.mapconductor.core.circle

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mapconductor.core.calculateZIndex
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.geometry.circleToRing
import com.mapconductor.core.polygon.toMercatorRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * 円のタイル描画を支える部品（画素を見ない部分）。
 *
 * js-sdk-core の `CircleTiling.test.mjs`、ios-sdk の `CircleTilingTests` と 1 対 1 で対応する。
 * 画素そのものは端末で見る（androidTest の `CircleTileRendererDeviceTest`）。
 *
 * - しきい値: 円の数だけ（頂点が無い）。判定はここ 1 か所。
 * - タイルにできないもの: 半径 0 以下・非有限、極（とメルカトルの外）にかかる、日付変更線をまたぐ。
 *   geodesic はどちらでもタイルにできる。
 * - 外接範囲はネイティブの 128 角形（circleToRing）と同じ式。
 * - ズームごとの形: 直径 1 px 未満は点、メルカトルのゆがみが 0.25 px 以内なら真円、それ以外は 128 角形。
 */
class CircleTilingTest {
    private val boston = GeoPoint.fromLatLong(42.3555, -71.0605)

    private fun circle(
        center: GeoPoint = boston,
        radius: Double = 5.0,
        geodesic: Boolean = true,
        zIndex: Int? = null,
        fill: Color = Color(0x80E8590C),
        stroke: Color = Color(0xFFE8590C),
        strokeWidth: Float = 1f,
    ) = CircleState(
        center = center,
        radiusMeters = radius,
        geodesic = geodesic,
        zIndex = zIndex,
        fillColor = fill,
        strokeColor = stroke,
        strokeWidth = strokeWidth.dp,
    )

    @Test
    fun `円の数が届けばタイルにする`() {
        val o = CircleTilingOptions.Default
        assertFalse(o.shouldUseTiles(1999))
        assertFalse(o.shouldUseTiles(2000))
        assertTrue(o.shouldUseTiles(2001))
        assertFalse(CircleTilingOptions.Disabled.shouldUseTiles(1_000_000))
        assertTrue(CircleTilingOptions(minCircleCount = 3).shouldUseTiles(3))
    }

    @Test
    fun `compact state preserves snapshot isolation and mutation notifications`() {
        val state = circle()
        var notifications = 0
        state.mutations.listen { notifications++ }
        val before = state.radiusMeters
        val snapshot = androidx.compose.runtime.snapshots.Snapshot.takeMutableSnapshot()
        try {
            snapshot.enter {
                state.radiusMeters = before + 1
                state.extra = 42L
                assertEquals(before + 1, state.radiusMeters, 0.0)
            }
            assertEquals(before, state.radiusMeters, 0.0)
            snapshot.apply().check()
            assertEquals(before + 1, state.radiusMeters, 0.0)
            assertEquals(42L, state.extra)
            assertEquals(2, notifications)
            state.extra = 42L
            assertEquals(2, notifications)
        } finally { snapshot.dispose() }
    }

    @Test
    fun `change flags preserve independent readers and replacement states`() {
        val state = circle()
        val first = CircleEntity(Unit, state)
        val original = first.fingerPrint
        state.radiusMeters = 6.0
        assertEquals(2, first.changeMask(state))
        val second = CircleEntity(Unit, state)
        assertEquals(0, second.changeMask(state))
        assertEquals(2, first.changeMask(state))
        assertEquals("legacy provider snapshot stays stable", original, first.fingerPrint)
        assertEquals(0, second.changeMask(state.copy()))
        assertTrue(second.changeMask(state.copy(fillColor = Color.Red)) != 0)
        state.fillColor = Color.Red
        assertTrue(first.changeMask(state) and 64 != 0)
        assertTrue(second.changeMask(state) and 64 != 0)
    }


    @Test
    fun `半径 0 以下・非有限はタイルにしない`() {
        assertTrue(CircleTileSet.canTile(circle(radius = 0.1)))
        assertFalse(CircleTileSet.canTile(circle(radius = 0.0)))
        assertFalse(CircleTileSet.canTile(circle(radius = -1.0)))
        assertFalse(CircleTileSet.canTile(circle(radius = Double.NaN)))
        assertFalse(CircleTileSet.canTile(circle(radius = Double.POSITIVE_INFINITY)))
    }

    @Test
    fun `極・メルカトルの外・日付変更線にかかる円はタイルにしない`() {
        // 北緯 85 度、半径 10 km: メルカトルの端（85.0511 度）を越える
        assertFalse(CircleTileSet.canTile(circle(center = GeoPoint.fromLatLong(85.0, 0.0), radius = 10_000.0)))
        assertFalse(CircleTileSet.canTile(circle(center = GeoPoint.fromLatLong(-85.0, 0.0), radius = 10_000.0)))
        // 北緯 70 度、半径 200 km: 届かない
        assertTrue(CircleTileSet.canTile(circle(center = GeoPoint.fromLatLong(70.0, 20.0), radius = 200_000.0)))
        // 日付変更線: 東経 179.99 度、半径 2 km は東へ 180 度を越える
        assertFalse(CircleTileSet.canTile(circle(center = GeoPoint.fromLatLong(0.0, 179.99), radius = 2_000.0)))
        assertFalse(CircleTileSet.canTile(circle(center = GeoPoint.fromLatLong(0.0, -179.99), radius = 2_000.0)))
        assertTrue(CircleTileSet.canTile(circle(center = GeoPoint.fromLatLong(0.0, 179.9), radius = 2_000.0)))
        // 中心の経度は [-180, 180] に正規化して見る: 190 度は -170 度
        assertTrue(CircleTileSet.canTile(circle(center = GeoPoint.fromLatLong(0.0, 190.0), radius = 2_000.0)))
        // 局所平面（geodesic = false）でも同じ
        assertFalse(
            CircleTileSet.canTile(circle(center = GeoPoint.fromLatLong(0.0, 179.99), radius = 2_000.0, geodesic = false)),
        )
    }

    @Test
    fun `geodesic で極を含む円はタイルにしない`() {
        // 北緯 60 度、半径 3400 km: 極（30 度 ≒ 3340 km 先）を含む
        val c = circle(center = GeoPoint.fromLatLong(60.0, 0.0), radius = 3_400_000.0)
        assertFalse(CircleTileSet.canTile(c))
        assertNull(TileCircle.from(c))
    }

    @Test
    fun `外接範囲はネイティブの 128 角形と同じ`() {
        for (geodesic in listOf(true, false)) {
            for ((lat, radius) in listOf(42.3555 to 15.0, 42.3555 to 5_000.0, 70.0 to 200_000.0, -33.0 to 50_000.0)) {
                val state = circle(center = GeoPoint.fromLatLong(lat, 10.0), radius = radius, geodesic = geodesic)
                val tile = TileCircle.from(state)!!
                val ring = toMercatorRing(circleToRing(state.center, radius, geodesic, 1024))
                var minX = Double.POSITIVE_INFINITY
                var maxX = Double.NEGATIVE_INFINITY
                var minY = Double.POSITIVE_INFINITY
                var maxY = Double.NEGATIVE_INFINITY
                for (i in ring.indices step 2) {
                    minX = minOf(minX, ring[i])
                    maxX = maxOf(maxX, ring[i])
                    minY = minOf(minY, ring[i + 1])
                    maxY = maxOf(maxY, ring[i + 1])
                }
                val width = maxX - minX
                // 1024 角形の外接範囲と、幅の 0.1 % 以内で一致する（多角形は円の内側）
                val tolerance = width * 1e-3
                val label = "geodesic=$geodesic lat=$lat r=$radius"
                assertEquals(label, minX, tile.bbox[0], tolerance)
                assertEquals(label, minY, tile.bbox[1], tolerance)
                assertEquals(label, maxX, tile.bbox[2], tolerance)
                assertEquals(label, maxY, tile.bbox[3], tolerance)
            }
        }
    }

    @Test
    fun `直径 1 px 未満は 1 px の点、その位置は中心の画素`() {
        val tile = TileCircle.from(circle(radius = 5.0))!!
        // z10: 世界 262144 px。ボストンで 1 px ≒ 113 m
        val scale = 256.0 * (1 shl 10)
        val shape = simplifyCircle(tile, scale)
        assertTrue(shape is CircleTileShape.Dot)
        shape as CircleTileShape.Dot
        assertEquals(kotlin.math.floor(tile.x * scale), shape.x, 0.0)
        assertEquals(kotlin.math.floor(tile.y * scale), shape.y, 0.0)
    }

    @Test
    fun `街路樹はどのズームでも真円で、半径の画素数はズーム 1 段で 2 倍`() {
        val tile = TileCircle.from(circle(radius = 15.0))!!
        var previous = 0.0
        for (z in 16..22) {
            val shape = simplifyCircle(tile, 256.0 * (1 shl z))
            assertTrue("z$z", shape is CircleTileShape.Arc)
            shape as CircleTileShape.Arc
            if (previous > 0) assertEquals("z$z", previous * 2, shape.radius, 1e-9)
            previous = shape.radius
        }
        // z22 で 15 m は 1 m ≒ 0.028 m/px … 半径 500 px 前後（ボストンの緯度で）
        assertTrue("radius=$previous", previous in 400.0..800.0)
    }

    @Test
    fun `直径 4 px 未満は縁を描かない`() {
        val tile = TileCircle.from(circle(radius = 5.0))!!
        // z16 のボストンで 1 px ≒ 1.77 m: 半径 5 m は 2.8 px、直径 5.6 px
        val z16 = simplifyCircle(tile, 256.0 * (1 shl 16)) as CircleTileShape.Arc
        assertTrue(z16.outlined)
        // z15: 直径 2.8 px
        val z15 = simplifyCircle(tile, 256.0 * (1 shl 15)) as CircleTileShape.Arc
        assertFalse(z15.outlined)
    }

    @Test
    fun `高緯度の大きな円は 128 角形で、点は circleToRing の点`() {
        val state = circle(center = GeoPoint.fromLatLong(70.0, 20.0), radius = 200_000.0)
        val tile = TileCircle.from(state)!!
        val scale = 256.0 * (1 shl 6)
        val shape = simplifyCircle(tile, scale)
        assertTrue(shape is CircleTileShape.Ring)
        shape as CircleTileShape.Ring
        assertTrue(shape.outlined)
        // 北側の半径が南側より長い（メルカトルのゆがみ）
        val ryN = (tile.y - tile.northY) * scale
        val ryS = (tile.southY - tile.y) * scale
        assertTrue("ryN=$ryN ryS=$ryS", ryN - ryS > 1.0)
        // 残った点は、ネイティブの 128 角形の点を画素格子に丸めたもの
        val native = toMercatorRing(circleToRing(state.center, 200_000.0, true, 128))
        for (i in shape.points.indices step 2) {
            val px = shape.points[i]
            val py = shape.points[i + 1]
            var nearest = Double.POSITIVE_INFINITY
            for (j in native.indices step 2) {
                nearest = minOf(nearest, max(abs(native[j] * scale - px), abs(native[j + 1] * scale - py)))
            }
            assertTrue("($px, $py) は 128 角形の点から $nearest px", nearest <= 0.5)
        }
    }

    @Test
    fun `真円と 128 角形の切り替えはメルカトルのゆがみが 4 分の 1 px を越えたとき`() {
        val tile = TileCircle.from(circle(center = GeoPoint.fromLatLong(42.3555, -71.0605), radius = 5_000.0))!!
        // 5 km の円: 低いズームでは真円、高いズームでは 128 角形
        assertTrue(simplifyCircle(tile, 256.0 * (1 shl 8)) is CircleTileShape.Arc)
        assertTrue(simplifyCircle(tile, 256.0 * (1 shl 16)) is CircleTileShape.Ring)
    }

    @Test
    fun `並びは実効 zIndex（未指定は中心からの自動値）`() {
        val south = circle(center = GeoPoint.fromLatLong(42.0, -71.0))
        val north = circle(center = GeoPoint.fromLatLong(43.0, -71.0))
        assertEquals(calculateZIndex(south.center), effectiveZIndex(south))
        // 自動値では南が上（大きい）
        assertTrue(effectiveZIndex(south) > effectiveZIndex(north))
        assertEquals(7, effectiveZIndex(circle(zIndex = 7)))
    }

    @Test
    fun `見えない縁（透明・幅 0）は余白にも数えない`() {
        val noStroke = TileCircle.from(circle(stroke = Color.Transparent, strokeWidth = 6f))!!
        assertFalse(noStroke.strokeVisible)
        assertEquals(0f, CircleTilePainter.strokeWidth(noStroke))
        val zeroWidth = TileCircle.from(circle(strokeWidth = 0f))!!
        assertEquals(0f, CircleTilePainter.strokeWidth(zeroWidth))
        val visible = TileCircle.from(circle(strokeWidth = 6f))
        assertNotNull(visible)
        assertEquals(6f, CircleTilePainter.strokeWidth(visible!!))
    }

    @Test
    fun `タイルのレイヤーは決まった id`() {
        val state = circleTileRasterLayerState("http://127.0.0.1/tiles/c/256/1/{z}/{x}/{y}.png")
        assertEquals(CIRCLE_TILE_LAYER_ID, state.id)
        assertEquals("mc-circle-tiles", state.id)
    }
}
