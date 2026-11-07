package com.mapconductor.core.polygon

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.tileserver.TileServerRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * PolygonController と PolygonTiler の振り分け。js-sdk-core の PolygonController（tiler プロパティ）と同じ規則:
 * タイルにしたポリゴンは PolygonManager に残し（タップ用、元の形）、レンダラには渡さない。
 *
 * PolygonTileSet が本物のタイルサーバーに経路を作るので、端末で動かす。
 */
@RunWith(AndroidJUnit4::class)
class PolygonTilerDeviceTest {
    private fun p(
        latitude: Double,
        longitude: Double,
    ): GeoPointInterface = GeoPoint.fromLatLong(latitude, longitude)

    /** 0.001 度角の区画を n 個、ボストンに並べる。 */
    private fun parcels(
        n: Int,
        fill: Color = Color(0xFF2563EB),
    ): List<PolygonState> =
        List(n) { i ->
            val lat = 42.30 + (i / 100) * 0.0012
            val lng = -71.10 + (i % 100) * 0.0012
            PolygonState(
                points = listOf(p(lat, lng), p(lat, lng + 0.001), p(lat + 0.001, lng + 0.001), p(lat + 0.001, lng)),
                id = "parcel-$i",
                fillColor = fill,
            )
        }

    private class Renderer : PolygonOverlayRendererInterface<String> {
        val onMap = mutableSetOf<String>()

        override suspend fun onAdd(data: List<PolygonOverlayRendererInterface.AddParamsInterface>): List<String?> =
            data.map { it.state.id.also(onMap::add) }

        override suspend fun onChange(data: List<PolygonOverlayRendererInterface.ChangeParamsInterface<String>>): List<String?> =
            data.map { it.current.state.id }

        override suspend fun onRemove(data: List<PolygonEntityInterface<String>>) {
            data.forEach { onMap.remove(it.state.id) }
        }

        override suspend fun onPostProcess() {}
    }

    private class Controller(
        renderer: Renderer,
    ) : PolygonController<String>(PolygonManager(), renderer)

    private class Templates {
        val seen = mutableListOf<String?>()

        fun last() = seen.lastOrNull()
    }

    private fun controller(): Triple<Controller, Renderer, Templates> {
        val renderer = Renderer()
        val controller = Controller(renderer)
        val templates = Templates()
        controller.tiler =
            PolygonTiler().apply {
                onTemplate = { template, _ -> synchronized(templates) { templates.seen.add(template) } }
            }
        return Triple(controller, renderer, templates)
    }

    /** しきい値を超えたらネイティブには何も渡さず、タップ用にマネージャへ残す */
    @Test
    fun pastThresholdNothingGoesNative_andTapsStillHit() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            val states = parcels(2000)
            controller.add(states)
            assertTrue(renderer.onMap.isEmpty())
            assertEquals(2000, controller.polygonManager.allEntities().size)
            assertNotNull(templates.last())
            // タップ判定は元の形で
            assertEquals("parcel-101", controller.find(p(42.3017, -71.0983))?.state?.id)
            assertNull(controller.find(p(42.3011, -71.0995)))
        }

    /** しきい値を下回ったらネイティブに戻し、上回ったらネイティブから外す */
    @Test
    fun crossingThresholdMovesPolygonsBetweenNativeAndTiles() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            controller.add(parcels(1999))
            assertEquals(1999, renderer.onMap.size)
            assertNull(templates.last())

            controller.add(parcels(2000))
            assertTrue("タイルへ移ったらネイティブから消す", renderer.onMap.isEmpty())
            assertEquals(2000, controller.polygonManager.allEntities().size)
            assertNotNull(templates.last())

            controller.add(parcels(10))
            assertEquals(10, renderer.onMap.size)
            assertEquals("リストから消えたものは忘れる", 10, controller.polygonManager.allEntities().size)
            assertNull(templates.last())
        }

    /** タイルのポリゴンのその場の変更は、版を上げたテンプレートで知らせる */
    @Test
    fun inPlaceChangeOfTiledPolygonAnnouncesNewTemplate() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            val states = parcels(2000)
            controller.add(states)
            val before = templates.last()!!
            states[5].fillColor = Color(0xFF16A34A)
            controller.update(states[5])
            assertTrue(renderer.onMap.isEmpty())
            withTimeout(2000) {
                while (synchronized(templates) { templates.last() } == before) delay(5)
            }
            assertTrue(templates.last()!! != before)
            assertEquals(
                Color(0xFF16A34A),
                controller.polygonManager
                    .getEntity("parcel-5")
                    ?.state
                    ?.fillColor,
            )
        }

    /** タイルはタイルサーバーから PNG で届き、何も無い場所は透明の 200 */
    @Test
    fun tilesAreServedAsPng_andEmptyPlacesAsTransparent200() =
        runBlocking {
            val (controller, _, templates) = controller()
            controller.add(parcels(2000))
            val template = templates.last()!!
            // z14 で区画の並ぶ場所と、何も無い南半球
            val (x, y) = tileOf(42.31, -71.07, 14)
            val drawn = get(template, 14, x, y)
            assertEquals(200, drawn.first)
            val bitmap = BitmapFactory.decodeByteArray(drawn.second, 0, drawn.second.size)
            assertNotNull(bitmap)
            var painted = 0
            for (py in 0 until bitmap.height step 4) {
                for (px in 0 until bitmap.width step 4) {
                    if (bitmap.getPixel(px, py) ushr 24 !=
                        0
                    ) {
                        painted++
                    }
                }
            }
            assertTrue("区画が描かれている: $painted", painted > 0)
            val empty = get(template, 14, 100, 12000)
            assertEquals(200, empty.first)
        }

    /** clear でテンプレートを外し、タイル側のポリゴンも忘れる */
    @Test
    fun clearRemovesTemplateAndForgetsTiledPolygons() =
        runBlocking {
            val (controller, _, templates) = controller()
            controller.add(parcels(2000))
            controller.clear()
            assertNull(templates.last())
            assertTrue(controller.polygonManager.allEntities().isEmpty())
            assertTrue(TileServerRegistry.get().isRunning())
        }

    private fun tileOf(
        lat: Double,
        lng: Double,
        z: Int,
    ): Pair<Int, Int> {
        val n = 1 shl z
        val x = ((lng + 180) / 360 * n).toInt()
        val s = kotlin.math.sin(Math.toRadians(lat))
        val y = ((0.5 - kotlin.math.ln((1 + s) / (1 - s)) / (4 * Math.PI)) * n).toInt()
        return x to y
    }

    private fun get(
        template: String,
        z: Int,
        x: Int,
        y: Int,
    ): Pair<Int, ByteArray> {
        val url = template.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.useCaches = false
        return try {
            val code = connection.responseCode
            code to connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
