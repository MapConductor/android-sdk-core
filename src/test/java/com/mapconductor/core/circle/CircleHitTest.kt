package com.mapconductor.core.circle

import androidx.compose.ui.unit.dp
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.spherical.Spherical
import com.mapconductor.core.spherical.calculateMetersPerPixel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * 円のタップ判定。タイルで描いていてもネイティブで描いていても同じ（元の円に対して見る）。
 *
 * 当たるのは「中心からの距離 ≤ 半径 + 縁幅 / 2（画面上の大きさ、カメラのズームで）」。カメラが
 * 無ければ半径だけ。指の大きさは足さない（面はポリゴンと同じく中だけ）。複数当たれば実効 zIndex の
 * 大きいもの、同じなら中心の近いもの。clickable = false は素通り。緯度の事前判定で結果は変わらない。
 *
 * js-sdk-core の `CircleHit.test.mjs`、ios-sdk の `CircleHitTests` と 1 対 1 で対応する。
 */
class CircleHitTest {
    private val lat = 42.3550
    private val lng = -71.0600
    private val camera = MapCameraPosition(position = GeoPoint.fromLatLong(lat, lng), zoom = 16.0)
    private val metersPerPixel = calculateMetersPerPixel(lat, 16.0)

    /** 北へ [meters] m（球面、Spherical.computeOffset と同じ）。 */
    private fun north(
        meters: Double,
        from: GeoPoint = GeoPoint.fromLatLong(lat, lng),
    ) = Spherical.computeOffset(from, meters, 0.0)

    private fun circle(
        id: String,
        radius: Double = 10.0,
        center: GeoPoint = GeoPoint.fromLatLong(lat, lng),
        strokeWidth: Float = 1f,
        zIndex: Int? = 0,
        clickable: Boolean = true,
    ) = CircleState(
        center = center,
        radiusMeters = radius,
        id = id,
        strokeWidth = strokeWidth.dp,
        zIndex = zIndex,
        clickable = clickable,
    )

    private fun managerWith(vararg states: CircleState): CircleManager<Unit> =
        CircleManager<Unit>().apply { states.forEach { registerEntity(CircleEntity(circle = Unit, state = it)) } }

    @Test
    fun `半径と縁幅の半分の内なら当たり、外なら外れ`() {
        // 縁幅 10 dp: z16 で 5 px ≒ 8.9 m 外まで
        val m = managerWith(circle("a", radius = 10.0, strokeWidth = 10f))
        val edge = 10.0 + 5.0 * metersPerPixel
        assertEquals("a", m.find(north(edge - 0.2), camera)?.state?.id)
        assertNull(m.find(north(edge + 0.2), camera))
        assertEquals("a", m.find(north(0.0), camera)?.state?.id)
    }

    @Test
    fun `カメラが無ければ半径だけ`() {
        val m = managerWith(circle("a", radius = 10.0, strokeWidth = 10f))
        assertEquals("a", m.find(north(9.9))?.state?.id)
        assertNull(m.find(north(10.1)))
        assertNull(m.find(north(10.1), null))
    }

    @Test
    fun `指の大きさは足さない`() {
        // 縁幅 0: 半径のすぐ外は外れ（tapTolerance 14 dp ≒ 25 m を足していない）
        val m = managerWith(circle("a", radius = 10.0, strokeWidth = 0f))
        assertNull(m.find(north(10.5), camera))
    }

    @Test
    fun `同じ zIndex なら中心の近いもの`() {
        val a = circle("a", radius = 20.0, center = north(-5.0))
        val b = circle("b", radius = 20.0, center = north(5.0))
        assertEquals("b", managerWith(a, b).find(north(3.0), camera)?.state?.id)
        assertEquals("b", managerWith(b, a).find(north(3.0), camera)?.state?.id)
        assertEquals("a", managerWith(a, b).find(north(-3.0), camera)?.state?.id)
    }

    @Test
    fun `zIndex の大きいものが先`() {
        val near = circle("near", radius = 20.0, center = north(5.0), zIndex = 0)
        val far = circle("far", radius = 20.0, center = north(-5.0), zIndex = 1)
        assertEquals("far", managerWith(near, far).find(north(3.0), camera)?.state?.id)
        // 未指定は中心からの自動値（南ほど大きい）
        val autoNorth = circle("autoNorth", radius = 20.0, center = north(5.0), zIndex = null)
        val autoSouth = circle("autoSouth", radius = 20.0, center = north(-5.0), zIndex = null)
        assertEquals("autoSouth", managerWith(autoNorth, autoSouth).find(north(3.0), camera)?.state?.id)
    }

    @Test
    fun `clickable = false は素通りして下の円に当たる`() {
        val top = circle("top", radius = 20.0, zIndex = 5, clickable = false)
        val under = circle("under", radius = 20.0, zIndex = 0)
        assertEquals("under", managerWith(top, under).find(north(1.0), camera)?.state?.id)
        assertNull(managerWith(top).find(north(1.0), camera))
    }

    @Test
    fun `緯度の事前判定で結果は変わらない`() {
        val random = Random(42)
        val states =
            List(3000) { i ->
                circle(
                    "c$i",
                    radius = 1.0 + random.nextDouble() * 14.0,
                    center = GeoPoint.fromLatLong(lat + (random.nextDouble() - 0.5) * 0.01, lng + (random.nextDouble() - 0.5) * 0.01),
                    strokeWidth = random.nextInt(0, 6).toFloat(),
                    zIndex = random.nextInt(0, 3),
                )
            }
        val m = managerWith(*states.toTypedArray())
        repeat(500) {
            val tap = GeoPoint.fromLatLong(lat + (random.nextDouble() - 0.5) * 0.01, lng + (random.nextDouble() - 0.5) * 0.01)
            val expected =
                states
                    .map { it to Spherical.computeDistanceBetween(it.center, tap) }
                    .filter { (s, d) -> d <= s.radiusMeters + metersPerPixel * s.strokeWidth.value / 2.0 }
                    .sortedWith(compareByDescending<Pair<CircleState, Double>> { it.first.zIndex!! }.thenBy { it.second })
                    .firstOrNull()
                    ?.first
                    ?.id
            assertEquals(expected, m.find(tap, camera)?.state?.id)
        }
    }
}
