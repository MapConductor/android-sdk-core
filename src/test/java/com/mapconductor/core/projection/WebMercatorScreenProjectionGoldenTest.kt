package com.mapconductor.core.projection

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapCameraPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [WebMercatorScreenProjection] を android と iOS で同じ値に留める。
 *
 * ## なぜテストで留めるのか
 *
 * この式のズレは**目視ではまず見つからない**。吹き出しが数 px ずれるだけで、
 * 地図は正しく動いているように見える。しかも
 *
 *   **px と dp を取り違えてもコンパイルは通る。**
 *
 * [WebMercatorScreenProjection.WORLD_SIZE_AT_ZOOM_0] の 256 は密度非依存の単位
 * （dp / CSS ピクセル）なので、端末ピクセルの大きさをそのまま渡すと結果が
 * density 倍ずれる。実際に android-for-longdo でこれを踏んで、吹き出しが
 * 右下へずれた（`LongdoMapViewHolder` が density で割ってから渡している）。
 * [`zoom0 の世界の幅が 256`][worldIsTwoFiftySixUnitsWideAtZoomZero] がその門番。
 *
 * ## 対応表
 *
 * ios-sdk-core の `WebMercatorScreenProjectionGoldenTests.swift` と同じ題・同じ値。
 * **片方だけ直す、ということをしないこと。**
 *
 * 元は js-sdk-react にも同じ 6 件があったが、JS 側で投影するのをやめた
 * （投影はコア層の仕事で、JS 層で判断しない）ため実装ごと消した。
 * 最初の 6 件はそのときの題をそのまま引き継いでいる。
 */
class WebMercatorScreenProjectionGoldenTest {
    private val width = 400f
    private val height = 800f

    private fun camera(
        latitude: Double = 35.681236,
        longitude: Double = 139.767125,
        zoom: Double = 12.0,
        bearing: Double = 0.0,
    ) = MapCameraPosition(
        position = GeoPoint(latitude = latitude, longitude = longitude),
        zoom = zoom,
        bearing = bearing,
    )

    private fun project(
        point: GeoPoint,
        camera: MapCameraPosition = camera(),
        width: Float = this.width,
        height: Float = this.height,
    ): Offset? = WebMercatorScreenProjection.toScreenOffset(point, camera, width, height)

    /** Float で丸められるので JS/Swift の 1e-6 より緩い。座標の桁では 1e-3 で十分。 */
    private fun assertClose(
        expected: Double,
        actual: Float,
        tolerance: Double = 1e-3,
    ) {
        assertTrue("expected=$expected actual=$actual", abs(expected - actual) < tolerance)
    }

    @Test
    fun `中心はビューの中央に来る`() {
        val c = camera()
        val p = project(c.position, c)!!
        assertClose(200.0, p.x)
        assertClose(400.0, p.y)
    }

    @Test
    fun `東は右、北は上`() {
        val c = camera()
        val east = project(GeoPoint(c.position.latitude, c.position.longitude + 0.05), c)!!
        val north = project(GeoPoint(c.position.latitude + 0.05, c.position.longitude), c)!!
        assertTrue("east.x=${east.x}", east.x > 200f)
        assertClose(400.0, east.y)
        assertTrue("north.y=${north.y}", north.y < 400f)
        assertClose(200.0, north.x)
    }

    @Test
    fun `ズームが 1 上がると中心からの距離が 2 倍になる`() {
        val target = GeoPoint(latitude = 35.681236, longitude = 139.8)
        val a = project(target, camera(zoom = 12.0))!!
        val b = project(target, camera(zoom = 13.0))!!
        val ratio = (b.x - 200f) / (a.x - 200f)
        assertTrue("ratio=$ratio", abs(ratio - 2f) < 1e-4)
    }

    /**
     * bearing は「地図を時計回りに回す量」。90 なら地図が右へ 90 度回り、
     * 画面の上には**西**が来る（東は画面の下）。
     */
    @Test
    fun `bearing=90 では西が上に来る`() {
        val c = camera(bearing = 90.0)
        val west = project(GeoPoint(c.position.latitude, c.position.longitude - 0.05), c)!!
        assertTrue("west.y=${west.y}", west.y < 400f)
        assertClose(200.0, west.x)

        val east = project(GeoPoint(c.position.latitude, c.position.longitude + 0.05), c)!!
        assertTrue("east.y=${east.y}", east.y > 400f)
    }

    @Test
    fun `日付変更線をまたいでも短いほうへ回る`() {
        val c = camera(latitude = 0.0, longitude = 179.9, zoom = 8.0)
        val across = project(GeoPoint(latitude = 0.0, longitude = -179.9), c)!!
        // 0.2 度ぶんだけ右にあるべき。地図の反対側（数万 px）へ飛ばない。
        assertTrue("x=${across.x}", across.x > 200f && across.x < 400f)
    }

    @Test
    fun `ビューの大きさが未確定なら null`() {
        assertNull(project(GeoPoint(0.0, 0.0), camera(), width = 0f, height = 0f))
    }

    /**
     * zoom 0 では世界一周がちょうど 256 単位。**渡した大きさと同じ単位で返る**ことの確認。
     *
     * 端末ピクセルを渡すと、この 256 が「256 px」の意味になってしまい、
     * density 倍ずれる。ここが px/dp 取り違えの唯一の機械的な門番。
     */
    @Test
    fun worldIsTwoFiftySixUnitsWideAtZoomZero() {
        val c = camera(latitude = 0.0, longitude = 0.0, zoom = 0.0)
        // 経度 +90° は世界の 1/4 ＝ 64 単位ぶん右。
        val quarter = project(GeoPoint(latitude = 0.0, longitude = 90.0), c)!!
        assertClose(200.0 + 64.0, quarter.x)
        assertClose(400.0, quarter.y)
    }

    @Test
    fun `画面座標へ変換して戻すと元の地理座標に戻る`() {
        val c = camera(bearing = 33.0)
        val target = GeoPoint(latitude = 35.7, longitude = 139.8)
        val screen = project(target, c)!!
        val back = WebMercatorScreenProjection.fromScreenOffset(screen, c, width, height)
        assertNotNull(back)
        assertEquals(target.latitude, back!!.latitude, 1e-4)
        assertEquals(target.longitude, back.longitude, 1e-4)
    }
}
