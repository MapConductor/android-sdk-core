package com.mapconductor.core.polyline

import androidx.compose.ui.unit.dp
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.spherical.Planar
import com.mapconductor.core.spherical.calculateMetersPerPixel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * ポリラインのタップ判定。タイルで描いていてもネイティブで描いていても同じ。
 *
 * 当たるのは「線の縁から指の大きさ（tapTolerance）以内」: 中心線から tapTolerance + 線幅 / 2
 * （どちらも画面上の大きさ）。複数当たれば一番近い線。clickable = false の線は素通り。
 * 5 万本を毎回測らないよう、外接矩形（許容ぶん広げる）に入らない線は先に飛ばす。飛ばしても結果は変わらない。
 *
 * js-sdk-core の `PolylineHitTolerance.test.mjs`、ios-sdk の `PolylineHitToleranceTests` と 1 対 1 で対応する。
 * ユニットテストでは density = 1（ResourceProvider のフォールバック）、tapTolerance は 14 dp = 14 px。
 */
class PolylineHitToleranceTest {
    private val lat = 42.3550
    private val camera = MapCameraPosition(position = GeoPoint.fromLatLong(lat, -71.06), zoom = 16.0)
    private val metersPerPixel = calculateMetersPerPixel(lat, 16.0)

    /** 北へ [meters] m。 */
    private fun north(meters: Double) = meters / 111_320.0

    private fun line(
        id: String,
        offsetMeters: Double = 0.0,
        width: Float = 1f,
        clickable: Boolean = true,
    ) = PolylineState(
        points = listOf(GeoPoint(lat + north(offsetMeters), -71.0610), GeoPoint(lat + north(offsetMeters), -71.0590)),
        id = id,
        strokeWidth = width.dp,
        clickable = clickable,
    )

    private fun managerWith(vararg states: PolylineState): PolylineManager<Unit> =
        PolylineManager<Unit>().apply {
            states.forEach { registerEntity(PolylineEntity(polyline = Unit, state = it)) }
        }

    private fun tapNorth(meters: Double) = GeoPoint(lat + north(meters), -71.0600)

    @Test
    fun `太い線は縁から指の大きさ以内なら当たり、細い線は同じ距離で外れる`() {
        // z16 で約 1.77 m/px。細い線（1 px）: 14.5 px ≒ 25.6 m、太い線（10 px）: 19 px ≒ 33.5 m
        val thirty = 30.0
        assertEquals(
            "wide",
            managerWith(line("wide", width = 10f))
                .find(tapNorth(thirty), camera)
                ?.entity
                ?.state
                ?.id,
        )
        assertNull(managerWith(line("thin", width = 1f)).find(tapNorth(thirty), camera))
        // 境目の内と外
        val edge = (14.0 + 5.0) * metersPerPixel
        assertEquals(
            "wide",
            managerWith(line("wide", width = 10f))
                .find(tapNorth(edge - 1.0), camera)
                ?.entity
                ?.state
                ?.id,
        )
        assertNull(managerWith(line("wide", width = 10f)).find(tapNorth(edge + 1.0), camera))
    }

    @Test
    fun `当たった点は線上の最近点`() {
        val hit = managerWith(line("a", width = 4f)).find(tapNorth(10.0), camera)!!
        assertEquals(lat, hit.closestPoint.latitude, 1e-7)
        assertEquals(-71.0600, hit.closestPoint.longitude, 1e-7)
    }

    @Test
    fun `複数当たれば一番近い線`() {
        val manager = managerWith(line("south", 0.0, width = 6f), line("north", 20.0, width = 2f))
        assertEquals(
            "north",
            manager
                .find(tapNorth(15.0), camera)
                ?.entity
                ?.state
                ?.id,
        )
        assertEquals(
            "south",
            manager
                .find(tapNorth(5.0), camera)
                ?.entity
                ?.state
                ?.id,
        )
    }

    @Test
    fun `clickable = false の線は素通りして下の線に当たる`() {
        val manager = managerWith(line("under", 0.0), line("over", 2.0, clickable = false))
        assertEquals(
            "under",
            manager
                .find(tapNorth(2.0), camera)
                ?.entity
                ?.state
                ?.id,
        )
        assertNull(managerWith(line("over", 2.0, clickable = false)).find(tapNorth(2.0), camera))
    }

    @Test
    fun `外接矩形で飛ばしても、全部測ったときと同じ線が当たる`() {
        val random = Random(7)
        val states =
            List(300) { i ->
                val lat0 = lat + random.nextDouble(-0.01, 0.01)
                val lng0 = -71.06 + random.nextDouble(-0.01, 0.01)
                val points =
                    List(2 + random.nextInt(4)) {
                        GeoPoint(lat0 + random.nextDouble(-0.001, 0.001), lng0 + random.nextDouble(-0.001, 0.001))
                    }
                PolylineState(points = points, id = "l$i", strokeWidth = random.nextInt(1, 8).dp)
            }
        val manager = managerWith(*states.toTypedArray())
        var hits = 0
        repeat(400) {
            val tap = GeoPoint(lat + random.nextDouble(-0.011, 0.011), -71.06 + random.nextDouble(-0.011, 0.011))
            val expected = bruteForce(states, tap)
            val actual =
                manager
                    .find(tap, camera)
                    ?.entity
                    ?.state
                    ?.id
            assertEquals(expected, actual)
            if (actual != null) hits++
        }
        // 当たりも外れも十分に含む
        assert(hits in 40..360) { "hits=$hits" }
    }

    /** 外接矩形を使わず、全部の線の全部の辺を測る。 */
    private fun bruteForce(
        states: List<PolylineState>,
        tap: GeoPoint,
    ): String? {
        var best: String? = null
        var bestDistance = Double.POSITIVE_INFINITY
        val mpp = calculateMetersPerPixel(tap.latitude, 16.0)
        for (s in states) {
            val threshold = mpp * (14.0 + s.strokeWidth.value / 2.0)
            for (i in 0 until s.points.size - 1) {
                val hit = Planar.pointOnLineOrNull(s.points[i], s.points[i + 1], tap, threshold) ?: continue
                if (hit.second < bestDistance) {
                    bestDistance = hit.second
                    best = s.id
                }
            }
        }
        return best
    }
}
