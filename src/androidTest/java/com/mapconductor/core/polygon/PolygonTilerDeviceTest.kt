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
        val added = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val removed = mutableListOf<String>()

        override suspend fun onAdd(data: List<PolygonOverlayRendererInterface.AddParamsInterface>): List<String?> =
            data.map { params ->
                params.state.id.also {
                    onMap.add(it)
                    added.add(it)
                }
            }

        override suspend fun onChange(data: List<PolygonOverlayRendererInterface.ChangeParamsInterface<String>>): List<String?> =
            data.map { params -> params.current.state.id.also { changed.add(it) } }

        override suspend fun onRemove(data: List<PolygonEntityInterface<String>>) {
            data.forEach {
                onMap.remove(it.state.id)
                removed.add(it.state.id)
            }
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

    // ── タイルにできなくなったポリゴン（geodesic・経度の幅 180° 以上・頂点 3 未満） ──────
    //
    // タイルにするかは add でだけ決めていた。タイルで描いているポリゴンをその場で
    // geodesic=true にしたり、点を書き換えてタイルにできない形にしたりすると、update は
    // タイルの経路に入ったまま canTile を見直さず、直線のままタイルに描き続けた。
    // js-sdk-core の PolygonTilingInPlace.test.mjs、ios-sdk-core の PolygonTilingInPlaceTests と同じ内容。

    /** parcel-101 の中と、隣の parcel-102 の中 */
    private val inside101 = 42.3017 to -71.0983
    private val inside102 = 42.3017 to -71.0971

    /** 混在リストでは geodesic だけネイティブ、タイルには描かれない */
    @Test
    fun inAMixedListOnlyTheGeodesicOneGoesNative() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            val g =
                PolygonState(
                    points = listOf(p(42.29, -71.10), p(42.29, -71.099), p(42.291, -71.099), p(42.291, -71.10)),
                    id = "g",
                    fillColor = Color(0xFFDC2626),
                    geodesic = true,
                )
            controller.add(parcels(2000) + g)
            assertEquals(setOf("g"), renderer.onMap)
            val template = templates.last()!!
            assertEquals("タイルには描かない", 0, alphaAt(template, 42.2905, -71.0995, 17))
            assertTrue("区画はタイルに描く", alphaAt(template, inside101.first, inside101.second, 17) > 0)
        }

    private suspend fun movesToNative(change: (PolygonState) -> Unit): Triple<Controller, Renderer, List<PolygonState>> {
        val (controller, renderer, templates) = controller()
        // しきい値（2,000）より 1 つ多い。移った 1 つを除いても、渡し直したときに残りはタイルのまま。
        val states = parcels(2001)
        controller.add(states)
        assertTrue(renderer.onMap.isEmpty())
        val before = templates.last()!!
        assertTrue(alphaAt(before, inside101.first, inside101.second, 17) > 0)

        change(states[101])
        controller.update(states[101])
        withTimeout(2000) {
            while (synchronized(templates) { templates.last() } == before) delay(5)
        }

        assertEquals("ネイティブで描く", listOf("parcel-101"), renderer.added)
        assertEquals("parcel-101", controller.polygonManager.getEntity("parcel-101")?.polygon)
        val after = templates.last()!!
        assertEquals("タイルから消える", 0, alphaAt(after, inside101.first, inside101.second, 17))
        assertTrue("隣はタイルに残る", alphaAt(after, inside102.first, inside102.second, 17) > 0)
        return Triple(controller, renderer, states)
    }

    /** その場で geodesic=true にしたらネイティブへ移り、タイルから消える。タップは引き続き当たる */
    @Test
    fun settingGeodesicInPlaceMovesItToNative() =
        runBlocking {
            val (controller, renderer, states) = movesToNative { it.geodesic = true }

            val hit = controller.find(p(inside101.first, inside101.second))
            assertEquals("parcel-101", hit?.state?.id)
            assertEquals("parcel-101", hit?.polygon)
            assertEquals("parcel-102", controller.find(p(inside102.first, inside102.second))?.state?.id)

            // その後のその場の変更はネイティブへ
            states[101].fillColor = Color(0xFF16A34A)
            controller.update(states[101])
            assertEquals(listOf("parcel-101"), renderer.changed)

            // 同じリストを渡し直しても、ネイティブのまま（足しも消しもしない）
            controller.add(states)
            assertEquals(listOf("parcel-101"), renderer.added)
            assertTrue(renderer.removed.isEmpty())
            assertEquals(setOf("parcel-101"), renderer.onMap)
        }

    /** その場で点を書き換えて経度の幅 180° 以上にしても同じ */
    @Test
    fun pointsSpanningHalfTheWorldInPlaceMoveItToNative() =
        runBlocking {
            movesToNative {
                it.points = listOf(p(42.3012, -71.0988), p(42.3012, -71.0978), p(42.3022, -71.0978), p(42.3022, 120.0))
            }
            Unit
        }

    /** その場で点を 3 未満にしても同じ */
    @Test
    fun fewerThanThreePointsInPlaceMoveItToNative() =
        runBlocking {
            movesToNative { it.points = listOf(p(42.3012, -71.0988), p(42.3012, -71.0978)) }
            Unit
        }

    /** The alpha of the tile pixel at a coordinate. */
    private fun alphaAt(
        template: String,
        lat: Double,
        lng: Double,
        z: Int,
    ): Int {
        val (x, y) = tileOf(lat, lng, z)
        val (code, bytes) = get(template, z, x, y)
        assertEquals(200, code)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return 0
        val scale = 256.0 * (1 shl z)
        val s = kotlin.math.sin(Math.toRadians(lat))
        val px = (lng + 180) / 360 * scale - x * 256
        val py = (0.5 - kotlin.math.ln((1 + s) / (1 - s)) / (4 * Math.PI)) * scale - y * 256
        val k = bitmap.width / 256.0
        return bitmap.getPixel((px * k).toInt(), (py * k).toInt()) ushr 24
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
