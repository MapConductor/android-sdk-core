package com.mapconductor.core.shapetile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.circle.CircleTileFileStorage
import com.mapconductor.core.circle.CircleTilePainter
import com.mapconductor.core.circle.TileCircle
import com.mapconductor.core.circle.effectiveZIndex
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polygon.PolygonTilePainter
import com.mapconductor.core.polygon.TilePolygon
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.polyline.PolylineTilePainter
import com.mapconductor.core.polyline.TilePolyline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max
import android.graphics.Bitmap

/**
 * 図形タイルを GPU（[GlShapeRasterizer]）と Canvas で描き、同じ絵になることを確かめる。
 *
 * GPU は MSAA、Canvas は Skia のアンチエイリアスなので、縁の画素は 1 段ずれる。見るのは、縁を
 * 除いた所が一致すること。とくに食い違いやすい所: nonzero の塗り（穴）、半透明の線の継ぎ目と
 * 自己交差（2 度塗りしない）、丸い角と端、1 点に縮む図形、渡した順の重なり、128 角形で描く円。
 * ios-sdk-core の `MetalShapeRasterizerTests` と `MetalPathRasterizerTests` に対応する。
 */
@RunWith(AndroidJUnit4::class)
class ShapeTileGpuDeviceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @After
    fun restore() {
        ShapeTileRenderer.useGpu = true
    }

    private fun p(
        latitude: Double,
        longitude: Double,
    ): GeoPointInterface = GeoPoint.fromLatLong(latitude, longitude)

    /** 同じタイルを GPU と Canvas で描く。 */
    private fun <T : Any> both(
        renderer: () -> ShapeTileRenderer<T>,
        x: Int,
        y: Int,
        z: Int,
        ratio: Int = 1,
    ): Pair<Bitmap, Bitmap> {
        assertTrue("GLES 3 が無い", GlShapeRasterizer.isAvailable)
        ShapeTileRenderer.useGpu = true
        val gpu = renderer().renderBitmap(x, y, z, ratio)!!
        ShapeTileRenderer.useGpu = false
        val cpu = renderer().renderBitmap(x, y, z, ratio)!!
        ShapeTileRenderer.useGpu = true
        return gpu to cpu
    }

    /** 画素の何割が、どれかのチャンネルで [tolerance] を超えて違うか。 */
    private fun mismatch(
        a: Bitmap,
        b: Bitmap,
        tolerance: Int,
    ): Double {
        assertEquals(a.width, b.width)
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var bad = 0
        for (i in pa.indices) {
            var d = 0
            for (shift in intArrayOf(0, 8, 16, 24)) d = max(d, abs(((pa[i] ushr shift) and 0xFF) - ((pb[i] ushr shift) and 0xFF)))
            if (d > tolerance) bad++
        }
        return bad.toDouble() / pa.size
    }

    private fun polygons(states: List<PolygonState>) =
        ShapeTileRenderer(tileSize = 256, pixelScale = { 1f }, painter = PolygonTilePainter).apply {
            setItems(states.map(TilePolygon::from))
        }

    private fun polylines(states: List<PolylineState>) =
        ShapeTileRenderer(tileSize = 256, pixelScale = { 1f }, painter = PolylineTilePainter).apply {
            setItems(states.map(TilePolyline::from))
        }

    private fun circles(states: List<CircleState>) =
        ShapeTileRenderer(tileSize = 256, pixelScale = { 1f }, painter = CircleTilePainter).apply {
            setItems(states.sortedBy(::effectiveZIndex).map(TileCircle::from))
        }

    private fun box(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ) = listOf(p(south, west), p(south, east), p(north, east), p(north, west))

    private val colors = listOf(Color.Red, Color.Blue, Color.Green, Color(0xFFFF8800), Color(0xFF8800FF))

    /** 穴と枠線。穴は抜け、枠線は外周だけ。 */
    @Test
    fun polygonWithHolesMatchesTheCanvas() {
        val state =
            PolygonState(
                points = box(-60.0, -120.0, 60.0, 120.0),
                holes = listOf(box(-30.0, -80.0, 20.0, 0.0), box(-10.0, 20.0, 40.0, 90.0)),
                fillColor = Color.Blue.copy(alpha = 0.6f),
                strokeColor = Color.Red,
                strokeWidth = 3.dp,
            )
        val (gpu, cpu) = both({ polygons(listOf(state)) }, 0, 0, 0)
        assertEquals(0, gpu.getPixel(80, 128) ushr 24)
        assertTrue(mismatch(gpu, cpu, 48) < 0.02)
    }

    /** 半透明のポリゴンが重なる。渡した順に重なる。 */
    @Test
    fun translucentPolygonsStackInOrder() {
        val states =
            (0 until 60).map { i ->
                val lat = -60.0 + (i * 37 % 110)
                val lng = -150.0 + (i * 53 % 280)
                PolygonState(
                    points = listOf(p(lat, lng), p(lat + 10, lng + 25), p(lat + 25, lng + 5)),
                    fillColor = colors[i % 5].copy(alpha = 0.5f),
                    strokeColor = colors[(i + 2) % 5].copy(alpha = 0.7f),
                    strokeWidth = (1 + i % 3).dp,
                )
            }
        val (gpu, cpu) = both({ polygons(states) }, 0, 0, 0)
        assertTrue(mismatch(gpu, cpu, 48) < 0.03)
    }

    /** 半透明の太い線が自分と交差する。交差も継ぎ目も 1 度だけ塗る。 */
    @Test
    fun translucentSelfCrossingLineIsPaintedOnce() {
        val line =
            PolylineState(
                points = listOf(p(0.0, -140.0), p(0.0, 100.0), p(50.0, -60.0), p(-60.0, 0.0), p(60.0, 0.0)),
                strokeColor = Color.Red.copy(alpha = 0.5f),
                strokeWidth = 12.dp,
            )
        val (gpu, cpu) = both({ polylines(listOf(line)) }, 0, 0, 0)
        val crossing = gpu.getPixel(128, 128) ushr 24
        assertTrue("交差は 1 度だけ: alpha=$crossing", abs(crossing - 128) <= 3)
        assertTrue(mismatch(gpu, cpu, 48) < 0.02)
    }

    /** 多くの線（255 本を超えて番号が一巡する）。Retina のタイル。 */
    @Test
    fun manyLinesMatchTheCanvasOnRetinaTiles() {
        val lines =
            (0 until 600).map { i ->
                val lat = -70.0 + (i * 13 % 140)
                val lng = -170.0 + (i * 29 % 300)
                PolylineState(
                    points = listOf(p(lat, lng), p(lat + 4, lng + 20), p(lat - 6, lng + 40), p(lat + 2, lng + 70)),
                    strokeColor = colors[i % 5].copy(alpha = if (i % 3 == 0) 0.6f else 1f),
                    strokeWidth = (1 + i % 5).dp,
                )
            }
        val (gpu, cpu) = both({ polylines(lines) }, 0, 0, 0, ratio = 2)
        assertEquals(512, gpu.width)
        assertTrue(mismatch(gpu, cpu, 48) < 0.04)
    }

    /** 街の円（重なり・縁・半透明）。 */
    @Test
    fun cityCirclesMatchTheCanvas() {
        val states =
            (0 until 400).map { i ->
                CircleState(
                    center = p(42.30 + (i * 37 % 100) / 1000.0, -71.10 + (i * 53 % 100) / 1000.0),
                    radiusMeters = 20.0 + i * 7 % 300,
                    fillColor = colors[i % 5].copy(alpha = 0.3f + (i % 7) / 10f),
                    strokeColor = colors[(i + 1) % 5].copy(alpha = 0.8f),
                    strokeWidth = (1 + i % 3).dp,
                )
            }
        // ボストンを含む z13 のタイル。
        val (gpu, cpu) = both({ circles(states) }, 2479, 3030, 13)
        assertTrue(mismatch(gpu, cpu, 48) < 0.03)
    }

    /** 引いたズーム: 多くが 1 点。1 画素に重なった円は最後の 1 つ。 */
    @Test
    fun zoomedOutCircleDotsMatchTheCanvas() {
        val states =
            (0 until 20_000).map { i ->
                CircleState(
                    center = p(30.0 + (i * 7919 % 2000) / 100.0, -120.0 + (i * 104729 % 5000) / 100.0),
                    radiusMeters = 100.0 + i % 700,
                    fillColor = colors[i % 5].copy(alpha = 0.65f),
                    strokeColor = colors[i % 5].copy(alpha = 0.35f),
                    strokeWidth = 0.5.dp,
                )
            }
        val (gpu, cpu) = both({ circles(states) }, 0, 0, 1)
        assertTrue(mismatch(gpu, cpu, 48) < 0.03)
    }

    /** 高緯度の大きな円は 128 角形（ポリゴンと同じ道）で描く。 */
    @Test
    fun bentCirclesAreDrawnAsTheirPolygon() {
        val states =
            listOf(
                CircleState(center = p(68.0, 20.0), radiusMeters = 900_000.0, fillColor = Color.Blue.copy(alpha = 0.5f), strokeColor = Color.Red, strokeWidth = 3.dp),
                CircleState(center = p(10.0, -40.0), radiusMeters = 50_000.0, fillColor = Color.Green, strokeColor = Color.Black, strokeWidth = 1.dp),
            )
        val (gpu, cpu) = both({ circles(states) }, 0, 0, 0)
        assertTrue(mismatch(gpu, cpu, 48) < 0.02)
    }

    /**
     * 全国規模の集合はファイルに置かれ、GPU は記録をバイト列から直接読む
     * （[CircleTilePainter.recordAll]）。1 つずつ読む Canvas と同じ絵になる。
     */
    @Test
    fun fileBackedCirclesMatchTheCanvas() {
        val states =
            (0 until 30_000).map { i ->
                CircleState(
                    center = p(30.0 + (i * 7919 % 2000) / 100.0, -120.0 + (i * 104729 % 5000) / 100.0),
                    radiusMeters = 100.0 + i % 9000,
                    fillColor = colors[i % 5].copy(alpha = 0.65f),
                    strokeColor = colors[(i + 1) % 5].copy(alpha = 0.6f),
                    strokeWidth = (i % 3).dp,
                )
            }.sortedBy(::effectiveZIndex).map(TileCircle::from)
        fun renderer() =
            ShapeTileRenderer(tileSize = 256, pixelScale = { 1f }, painter = CircleTilePainter).apply {
                setStorage(CircleTileFileStorage.create(temporary.newFolder(), states.size, { states[it] }))
            }
        for ((z, x, y) in listOf(Triple(1, 0, 0), Triple(4, 3, 6), Triple(6, 12, 25))) {
            val (gpu, cpu) = both(::renderer, x, y, z)
            assertTrue("z=$z", mismatch(gpu, cpu, 48) < 0.03)
        }
    }
}
