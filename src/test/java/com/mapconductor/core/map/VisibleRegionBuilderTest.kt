package com.mapconductor.core.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildVisibleRegion] の意味論テスト。
 *
 * android-for-maplibre / mapbox / tomtom が各自持っていた「4 隅を逆投影して
 * [VisibleRegion] を組む」35 行前後をコアへ集約したもの。
 *
 * 押さえたい不変条件:
 *  - 隅の対応（nearLeft は左下、farLeft は左上）を取り違えない。
 *  - `requireAllCorners` の 2 つの挙動。TomTom は傾いた地図で隅の逆投影が地表に
 *    当たらないことがあり、そこで region ごと落とすと marker-clustering が
 *    ビューポートを算出できずクラスタが消える。
 */
class VisibleRegionBuilderTest {
    /** 画面座標をそのまま経度/緯度として返す代役（y を反転して「上が北」にする）。 */
    private class FakeHolder(
        val width: Float,
        val height: Float,
        private val unresolved: Set<Offset> = emptySet(),
    ) : MapViewHolderInterface<Any, Any> {
        override val mapView: Any = Any()
        override val map: Any = Any()

        val requested = mutableListOf<Offset>()

        override fun toScreenOffset(position: GeoPointInterface): Offset? = null

        override suspend fun fromScreenOffset(offset: Offset): GeoPoint? = fromScreenOffsetSync(offset)

        override fun fromScreenOffsetSync(offset: Offset): GeoPoint? {
            requested += offset
            if (offset in unresolved) return null
            // x -> 経度 (0..10), y -> 緯度 (上が +50、下が -50)
            val lng = offset.x / width * 10.0
            val lat = 50.0 - (offset.y / height) * 100.0
            return GeoPoint.fromLatLong(lat, lng)
        }
    }

    /** サイズを明示するオーバーロードを直接叩く（View を用意できないため）。 */
    private fun build(
        holder: FakeHolder,
        inset: Float = 0f,
        requireAllCorners: Boolean = true,
    ): VisibleRegion? = holder.buildVisibleRegion(Size(holder.width, holder.height), inset, requireAllCorners)

    @Test
    fun `隅の対応が正しい`() {
        val region = build(FakeHolder(100f, 200f))
        assertNotNull(region)
        region!!
        // nearLeft = 左下 = 経度 0 / 緯度 -50
        assertEquals(0.0, region.nearLeft!!.longitude, 1e-9)
        assertEquals(-50.0, region.nearLeft!!.latitude, 1e-9)
        // farRight = 右上 = 経度 10 / 緯度 +50
        assertEquals(10.0, region.farRight!!.longitude, 1e-9)
        assertEquals(50.0, region.farRight!!.latitude, 1e-9)
    }

    @Test
    fun `bounds が 4 隅すべてを含む`() {
        val region = build(FakeHolder(100f, 200f))!!
        listOf(region.nearLeft, region.nearRight, region.farLeft, region.farRight).forEach {
            assertTrue("bounds が $it を含んでいない", region.bounds.contains(it!!))
        }
    }

    @Test
    fun `inset を指定すると内側の点を使う`() {
        val holder = FakeHolder(100f, 200f)
        build(holder, inset = 1f)
        assertTrue(holder.requested.all { it.x == 1f || it.x == 99f })
        assertTrue(holder.requested.all { it.y == 1f || it.y == 199f })
    }

    @Test
    fun `requireAllCorners が true なら 1 隅でも欠けたら null`() {
        val holder = FakeHolder(100f, 200f, unresolved = setOf(Offset(0f, 0f)))
        assertNull(build(holder, requireAllCorners = true))
    }

    @Test
    fun `requireAllCorners が false なら解けた隅だけで組む`() {
        val holder = FakeHolder(100f, 200f, unresolved = setOf(Offset(0f, 0f)))
        val region = build(holder, requireAllCorners = false)
        assertNotNull("隅が欠けても region ごと落としてはいけない", region)
        assertNull("解けなかった隅は null のまま", region!!.farLeft)
        assertNotNull(region.nearLeft)
        assertTrue(region.bounds.contains(region.nearRight!!))
    }

    @Test
    fun `隅が 1 つも解けなければ null`() {
        val all = setOf(Offset(0f, 0f), Offset(100f, 0f), Offset(0f, 200f), Offset(100f, 200f))
        assertNull(build(FakeHolder(100f, 200f, unresolved = all), requireAllCorners = false))
    }
}
