package com.mapconductor.core.circle

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.tileserver.TileServerRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ln
import kotlin.math.sin
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * CircleController と CircleTiler の振り分け。PolylineTilerDeviceTest と同じ規則:
 * タイルにした円は CircleManager に残し（タップ用、元の円のまま）、レンダラには渡さない。
 * js-sdk-core の CircleController（tiler）、ios-sdk-core の CircleTilerTests と同じ内容。
 *
 * CircleTileSet が本物のタイルサーバーに経路を作るので、端末で動かす。
 */
@RunWith(AndroidJUnit4::class)
class CircleTilerDeviceTest {
    private fun p(
        latitude: Double,
        longitude: Double,
    ): GeoPointInterface = GeoPoint.fromLatLong(latitude, longitude)

    private val events = mutableListOf<String>()

    /** 半径 20 m の木を n 本、ボストンに 0.0012 度（東西 ≒ 99 m、南北 ≒ 133 m）おきに並べる。 */
    private fun trees(
        n: Int,
        color: Color = Color(0xFF12B886),
    ): List<CircleState> =
        List(n) { i ->
            val lat = 42.30 + (i / 100) * 0.0012
            val lng = -71.10 + (i % 100) * 0.0012
            CircleState(
                center = p(lat, lng),
                radiusMeters = 20.0,
                id = "tree-$i",
                fillColor = color,
                strokeColor = color,
                strokeWidth = 2.dp,
                zIndex = 0,
                onAdded = { events += "onAdded:${it.id}" },
            )
        }

    private inner class Renderer : CircleOverlayRendererInterface<String> {
        val onMap = mutableSetOf<String>()
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()

        override suspend fun onAdd(data: List<CircleOverlayRendererInterface.AddParamsInterface>): List<String?> =
            data.map { params ->
                events += "native:${params.state.id}"
                params.state.id.also {
                    onMap.add(it)
                    added.add(it)
                }
            }

        override suspend fun onChange(data: List<CircleOverlayRendererInterface.ChangeParamsInterface<String>>): List<String?> =
            data.map { it.current.state.id }

        override suspend fun onRemove(data: List<CircleEntityInterface<String>>) {
            data.forEach {
                onMap.remove(it.state.id)
                removed.add(it.state.id)
            }
        }

        override suspend fun onPostProcess() {}
    }

    private class Controller(
        renderer: CircleOverlayRendererInterface<String>,
    ) : CircleController<String>(CircleManager(), renderer)

    private class Templates {
        val seen = mutableListOf<String?>()

        fun last() = synchronized(this) { seen.lastOrNull() }
    }

    private fun controller(options: CircleTilingOptions = CircleTilingOptions.Default): Triple<Controller, Renderer, Templates> {
        val renderer = Renderer()
        val controller = Controller(renderer)
        val templates = Templates()
        controller.tiler =
            CircleTiler(options).apply {
                onTemplate = { template, _ -> synchronized(templates) { templates.seen.add(template) } }
            }
        return Triple(controller, renderer, templates)
    }

    /** しきい値を超えたらネイティブには何も渡さず、タップ用にマネージャへ残す */
    @Test
    fun pastThresholdNothingGoesNative_andTapsStillHit() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            controller.onCameraChanged(MapCameraPosition(position = p(42.30, -71.10), zoom = 16.0))
            controller.add(trees(2001))
            assertTrue(renderer.onMap.isEmpty())
            assertEquals(2001, controller.circleManager.allEntities().size)
            assertNotNull(templates.last())
            // tree-101: 北緯 42.3012、経度 -71.0988。中心から 15 m 東は当たる
            assertEquals("tree-101", controller.find(p(42.3012, -71.09862))?.state?.id)
            // 木と木の間は外れ
            assertNull(controller.find(p(42.3018, -71.0982)))
        }

    /** しきい値を下回ったらネイティブに戻し、上回ったらネイティブから外す */
    @Test
    fun crossingThresholdMovesCirclesBetweenNativeAndTiles() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            controller.add(trees(2000))
            assertEquals(2000, renderer.onMap.size)
            assertNull(templates.last())

            controller.add(trees(2001))
            assertTrue("タイルへ移ったらネイティブから消す", renderer.onMap.isEmpty())
            assertEquals(2001, controller.circleManager.allEntities().size)
            assertNotNull(templates.last())

            controller.add(trees(10))
            assertEquals(10, renderer.onMap.size)
            assertEquals("リストから消えたものは忘れる", 10, controller.circleManager.allEntities().size)
            assertNull(templates.last())
        }

    /** タイルの円のその場の変更は、版を上げたテンプレートで知らせる */
    @Test
    fun inPlaceChangeOfTiledCircleAnnouncesNewTemplate() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            val states = trees(2001)
            controller.add(states)
            val before = templates.last()!!
            states[5].fillColor = Color(0xFFE64980)
            controller.update(states[5])
            assertTrue(renderer.onMap.isEmpty())
            withTimeout(2000) {
                while (templates.last() == before) delay(5)
            }
            assertTrue(templates.last()!! != before)
            assertEquals(Color(0xFFE64980), controller.circleManager.getEntity("tree-5")?.state?.fillColor)
        }

    /** タイルはタイルサーバーから PNG で届き、何も無い場所は透明の 200。URL に版が入る */
    @Test
    fun tilesAreServedAsPng_andEmptyPlacesAsTransparent200() =
        runBlocking {
            val (controller, _, templates) = controller()
            controller.add(trees(2001))
            val template = templates.last()!!
            assertTrue("版が入る: $template", Regex("""/circles-\w+/256/\d+/\{z\}""").containsMatchIn(template))
            assertTrue(alphaAt(template, 42.3012, -71.0988, 17) > 0)
            assertEquals("木と木の間", 0, alphaAt(template, 42.3018, -71.0982, 17))
            assertEquals(200, get(template, 14, 100, 12000).first)
        }

    /** clear でテンプレートを外し、タイル側の円も忘れる */
    @Test
    fun clearRemovesTemplateAndForgetsTiledCircles() =
        runBlocking {
            val (controller, _, templates) = controller()
            controller.add(trees(2001))
            controller.clear()
            assertNull(templates.last())
            assertTrue(controller.circleManager.allEntities().isEmpty())
            assertTrue(TileServerRegistry.get().isRunning())
        }

    /** 混在リストでは日付変更線の円だけネイティブ、タイルには描かれない */
    @Test
    fun inAMixedListOnlyTheAntimeridianOneGoesNative() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            val dateline = CircleState(center = p(0.0, 179.999), radiusMeters = 5_000.0, id = "dateline")
            controller.add(trees(2001) + dateline)
            assertEquals(setOf("dateline"), renderer.onMap)
            assertTrue(alphaAt(templates.last()!!, 42.3012, -71.0988, 17) > 0)
        }

    /** その場で日付変更線へ動かしたらネイティブへ移り（add ではない、onAdded は呼ばない）、タイルから消える */
    @Test
    fun movingOntoTheAntimeridianInPlaceMovesItToNative() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            // One becomes native; the remaining 2,001 must still exceed 2,000.
            val states = trees(2002)
            controller.add(states)
            val before = templates.last()!!
            assertTrue(alphaAt(before, 42.3012, -71.0988, 17) > 0)
            events.clear()

            states[101].center = p(0.0, 179.999)
            states[101].radiusMeters = 5_000.0
            controller.update(states[101])
            withTimeout(2000) {
                while (templates.last() == before) delay(5)
            }
            assertEquals(listOf("tree-101"), renderer.added)
            assertEquals("tree-101", controller.circleManager.getEntity("tree-101")?.circle)
            assertEquals(listOf("native:tree-101"), events)
            val after = templates.last()!!
            assertEquals("タイルから消える", 0, alphaAt(after, 42.3012, -71.0988, 17))
            assertTrue("隣はタイルに残る", alphaAt(after, 42.3012, -71.0976, 17) > 0)

            // 同じリストを渡し直しても、ネイティブのまま（足しも消しもしない）
            controller.add(states)
            assertEquals(listOf("tree-101"), renderer.added)
            assertTrue(renderer.removed.isEmpty())
        }

    // ── onAdded と地図ごとの指定（CircleOverlay 経由、本物の BaseMapViewController） ──────

    private class Driver(
        val circles: Controller,
    ) : BaseMapViewController() {
        override val holder get() = throw UnsupportedOperationException()
        override val defaultCoroutine = CoroutineScope(Dispatchers.Unconfined)
        override val mainCoroutine = defaultCoroutine

        init {
            registerOverlayController(circles)
        }

        override fun moveCamera(position: MapCameraPosition) = Unit

        override fun animateCamera(
            position: MapCameraPosition,
            duration: Long,
        ) = Unit

        override fun fitBounds(
            bounds: GeoRectBounds,
            padding: Int,
        ) = Unit

        override suspend fun clearOverlays() = circles.clear()
    }

    private fun CircleOverlay.show(
        map: Driver,
        states: List<CircleState>,
    ) = runBlocking { render(states.associateBy { it.id }.toMutableMap(), map) }

    /** タイルで地図に載った円にも onAdded。その場の変更とネイティブへの移動では呼ばない */
    @Test
    fun onAddedOnTheTilePath() {
        val (controller, renderer, _) = controller(CircleTilingOptions(minCircleCount = 3))
        val map = Driver(controller)
        val overlay = CircleOverlay(MutableStateFlow(mutableMapOf()))
        val states = trees(4)
        overlay.show(map, states)
        assertEquals(listOf("onAdded:tree-0", "onAdded:tree-1", "onAdded:tree-2", "onAdded:tree-3"), events)
        assertTrue(renderer.onMap.isEmpty())
        events.clear()

        states[0].fillColor = Color.Red
        runBlocking { map.updateCircle(states[0]) }
        states[1].radiusMeters = 0.0
        runBlocking { map.updateCircle(states[1]) }
        overlay.show(map, states)
        assertEquals(listOf("native:tree-1"), events)
        controller.destroy()
    }

    /** 地図ごとの指定（CircleOverlay の tilingOptions）は、次に円を渡すときに効く */
    @Test
    fun perMapOptionsApplyOnTheNextHandOver() {
        val (controller, renderer, templates) = controller()
        val map = Driver(controller)
        val options = MutableStateFlow<CircleTilingOptions?>(CircleTilingOptions.Disabled)
        val overlay = CircleOverlay(MutableStateFlow(mutableMapOf()), options)
        val states = trees(2001)

        overlay.show(map, states)
        assertEquals("Disabled: 全部ネイティブ", 2001, renderer.onMap.size)
        assertNull(templates.last())

        options.value = CircleTilingOptions.Default
        overlay.show(map, states)
        assertTrue("Default: 全部タイル", renderer.onMap.isEmpty())
        assertNotNull(templates.last())
        assertEquals(CircleTilingOptions.Default, controller.tiler?.options)
        controller.destroy()
    }

    private fun alphaAt(
        template: String,
        lat: Double,
        lng: Double,
        z: Int,
    ): Int {
        val n = 1 shl z
        val s = sin(Math.toRadians(lat))
        val my = 0.5 - ln((1 + s) / (1 - s)) / (4 * Math.PI)
        val x = ((lng + 180) / 360 * n).toInt()
        val y = (my * n).toInt()
        val (code, bytes) = get(template, z, x, y)
        assertEquals(200, code)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return 0
        val scale = 256.0 * n
        val px = (lng + 180) / 360 * scale - x * 256
        val py = my * scale - y * 256
        val k = bitmap.width / 256.0
        return bitmap.getPixel((px * k).toInt(), (py * k).toInt()) ushr 24
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
            connection.responseCode to connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
