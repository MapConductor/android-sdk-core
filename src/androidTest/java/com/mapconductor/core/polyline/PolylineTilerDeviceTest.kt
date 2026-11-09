package com.mapconductor.core.polyline

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
 * PolylineController と PolylineTiler の振り分け。PolygonTilerDeviceTest と同じ規則:
 * タイルにした線は PolylineManager に残し（タップ用、元の点のまま）、レンダラには渡さない。
 * js-sdk-core の PolylineController（tiler）、ios-sdk-core の PolylineTilerTests と同じ内容。
 *
 * PolylineTileSet が本物のタイルサーバーに経路を作るので、端末で動かす。
 */
@RunWith(AndroidJUnit4::class)
class PolylineTilerDeviceTest {
    private fun p(
        latitude: Double,
        longitude: Double,
    ): GeoPointInterface = GeoPoint.fromLatLong(latitude, longitude)

    private val events = mutableListOf<String>()

    /** 東西に 0.001 度の道を n 本、ボストンに並べる。 */
    private fun roads(
        n: Int,
        color: Color = Color(0xFF2563EB),
    ): List<PolylineState> =
        List(n) { i ->
            val lat = 42.30 + (i / 100) * 0.0012
            val lng = -71.10 + (i % 100) * 0.0012
            PolylineState(
                points = listOf(p(lat, lng), p(lat, lng + 0.0005), p(lat, lng + 0.001)),
                id = "road-$i",
                strokeColor = color,
                strokeWidth = 4.dp,
                onAdded = { events += "onAdded:${it.id}" },
            )
        }

    private inner class Renderer : PolylineOverlayRendererInterface<String> {
        val onMap = mutableSetOf<String>()
        val added = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val removed = mutableListOf<String>()

        override suspend fun onAdd(data: List<PolylineOverlayRendererInterface.AddParamsInterface>): List<String?> =
            data.map { params ->
                events += "native:${params.state.id}"
                params.state.id.also {
                    onMap.add(it)
                    added.add(it)
                }
            }

        override suspend fun onChange(
            data: List<PolylineOverlayRendererInterface.ChangeParamsInterface<String>>,
        ): List<String?> =
            data.map { params ->
                params.current.state.id
                    .also { changed.add(it) }
            }

        override suspend fun onRemove(data: List<PolylineEntityInterface<String>>) {
            data.forEach {
                onMap.remove(it.state.id)
                removed.add(it.state.id)
            }
        }

        override suspend fun onPostProcess() {}
    }

    private class Controller(
        renderer: PolylineOverlayRendererInterface<String>,
    ) : PolylineController<String>(PolylineManager(), renderer)

    private class Templates {
        val seen = mutableListOf<String?>()

        fun last() = synchronized(this) { seen.lastOrNull() }
    }

    private fun controller(
        options: PolylineTilingOptions = PolylineTilingOptions.Default,
    ): Triple<Controller, Renderer, Templates> {
        val renderer = Renderer()
        val controller = Controller(renderer)
        val templates = Templates()
        controller.tiler =
            PolylineTiler(options).apply {
                onTemplate = { template, _ -> synchronized(templates) { templates.seen.add(template) } }
            }
        return Triple(controller, renderer, templates)
    }

    /** z16 のボストン（タップの許容距離を画面の大きさで測るため）。 */
    private suspend fun Controller.atZoom16() =
        onCameraChanged(MapCameraPosition(position = p(42.30, -71.10), zoom = 16.0))

    /** しきい値を超えたらネイティブには何も渡さず、タップ用にマネージャへ残す */
    @Test
    fun pastThresholdNothingGoesNative_andTapsStillHit() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            controller.atZoom16()
            controller.add(roads(2000))
            assertTrue(renderer.onMap.isEmpty())
            assertEquals(2000, controller.polylineManager.allEntities().size)
            assertNotNull(templates.last())
            // タップ判定は元の線で: road-101 は北緯 42.3012、経度 -71.0988..-71.0978
            val hit = controller.findWithClosestPoint(p(42.30125, -71.0983))
            assertEquals("road-101", hit?.entity?.state?.id)
            assertEquals(42.3012, hit!!.closestPoint.latitude, 1e-7)
            // 道と道の間（南北に 0.0012 度 ≒ 133 m 離れている）の真ん中は外れ
            assertNull(controller.find(p(42.3018, -71.0983)))
        }

    /** しきい値を下回ったらネイティブに戻し、上回ったらネイティブから外す */
    @Test
    fun crossingThresholdMovesPolylinesBetweenNativeAndTiles() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            controller.add(roads(1999))
            assertEquals(1999, renderer.onMap.size)
            assertNull(templates.last())

            controller.add(roads(2000))
            assertTrue("タイルへ移ったらネイティブから消す", renderer.onMap.isEmpty())
            assertEquals(2000, controller.polylineManager.allEntities().size)
            assertNotNull(templates.last())

            controller.add(roads(10))
            assertEquals(10, renderer.onMap.size)
            assertEquals("リストから消えたものは忘れる", 10, controller.polylineManager.allEntities().size)
            assertNull(templates.last())
        }

    /** タイルの線のその場の変更は、版を上げたテンプレートで知らせる */
    @Test
    fun inPlaceChangeOfTiledPolylineAnnouncesNewTemplate() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            val states = roads(2000)
            controller.add(states)
            val before = templates.last()!!
            states[5].strokeColor = Color(0xFF16A34A)
            controller.update(states[5])
            assertTrue(renderer.onMap.isEmpty())
            withTimeout(2000) {
                while (templates.last() == before) delay(5)
            }
            assertTrue(templates.last()!! != before)
            assertEquals(
                Color(0xFF16A34A),
                controller.polylineManager
                    .getEntity("road-5")
                    ?.state
                    ?.strokeColor,
            )
        }

    /** タイルはタイルサーバーから PNG で届き、何も無い場所は透明の 200 */
    @Test
    fun tilesAreServedAsPng_andEmptyPlacesAsTransparent200() =
        runBlocking {
            val (controller, _, templates) = controller()
            controller.add(roads(2000))
            val template = templates.last()!!
            assertTrue("版が入る: $template", Regex("""/polylines-\w+/256/\d+/\{z\}""").containsMatchIn(template))
            assertTrue(alphaAt(template, 42.3012, -71.0983, 17) > 0)
            assertEquals("道と道の間", 0, alphaAt(template, 42.3018, -71.0983, 17))
            val empty = get(template, 14, 100, 12000)
            assertEquals(200, empty.first)
        }

    /** clear でテンプレートを外し、タイル側の線も忘れる */
    @Test
    fun clearRemovesTemplateAndForgetsTiledPolylines() =
        runBlocking {
            val (controller, _, templates) = controller()
            controller.add(roads(2000))
            controller.clear()
            assertNull(templates.last())
            assertTrue(controller.polylineManager.allEntities().isEmpty())
            assertTrue(TileServerRegistry.get().isRunning())
        }

    /** 混在リストでは geodesic だけネイティブ、タイルには描かれない */
    @Test
    fun inAMixedListOnlyTheGeodesicOneGoesNative() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            val g =
                PolylineState(
                    points = listOf(p(42.2905, -71.10), p(42.2905, -71.099)),
                    id = "g",
                    strokeColor = Color(0xFFDC2626),
                    strokeWidth = 4.dp,
                    geodesic = true,
                )
            controller.add(roads(2000) + g)
            assertEquals(setOf("g"), renderer.onMap)
            val template = templates.last()!!
            assertEquals("タイルには描かない", 0, alphaAt(template, 42.2905, -71.0995, 17))
            assertTrue("道はタイルに描く", alphaAt(template, 42.3012, -71.0983, 17) > 0)
        }

    /** その場で geodesic にしたらネイティブへ移り（add ではない）、タイルから消える。隣はタイルに残る */
    @Test
    fun settingGeodesicInPlaceMovesItToNative() =
        runBlocking {
            val (controller, renderer, templates) = controller()
            val states = roads(2001)
            controller.add(states)
            val before = templates.last()!!
            assertTrue(alphaAt(before, 42.3012, -71.0983, 17) > 0)

            states[101].geodesic = true
            controller.update(states[101])
            withTimeout(2000) {
                while (templates.last() == before) delay(5)
            }
            assertEquals(listOf("road-101"), renderer.added)
            assertEquals("road-101", controller.polylineManager.getEntity("road-101")?.polyline)
            val after = templates.last()!!
            assertEquals("タイルから消える", 0, alphaAt(after, 42.3012, -71.0983, 17))
            assertTrue("隣はタイルに残る", alphaAt(after, 42.3012, -71.0971, 17) > 0)

            // 同じリストを渡し直しても、ネイティブのまま（足しも消しもしない）
            controller.add(states)
            assertEquals(listOf("road-101"), renderer.added)
            assertTrue(renderer.removed.isEmpty())
        }

    // ── onAdded と地図ごとの指定（PolylineOverlay 経由、本物の BaseMapViewController） ──────

    private class Driver(
        val polylines: Controller,
    ) : BaseMapViewController() {
        override val holder get() = throw UnsupportedOperationException()
        override val defaultCoroutine = CoroutineScope(Dispatchers.Unconfined)
        override val mainCoroutine = defaultCoroutine

        init {
            registerOverlayController(polylines)
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

        override suspend fun clearOverlays() = polylines.clear()
    }

    private fun PolylineOverlay.show(
        map: Driver,
        states: List<PolylineState>,
    ) = runBlocking { render(states.associateBy { it.id }.toMutableMap(), map) }

    /** タイルで地図に載った線にも onAdded。その場の変更とネイティブへの移動では呼ばない */
    @Test
    fun onAddedOnTheTilePath() {
        // 4 本で 3 本以上ならタイル: 1 本がネイティブへ移っても、残りはタイルのまま
        val (controller, renderer, _) = controller(PolylineTilingOptions(minPolylineCount = 3))
        val map = Driver(controller)
        val overlay = PolylineOverlay(MutableStateFlow(mutableMapOf()))
        val states = roads(4)
        overlay.show(map, states)
        assertEquals(listOf("onAdded:road-0", "onAdded:road-1", "onAdded:road-2", "onAdded:road-3"), events)
        assertTrue(renderer.onMap.isEmpty())
        events.clear()

        states[0].strokeColor = Color.Red
        runBlocking { map.updatePolyline(states[0]) }
        states[1].geodesic = true
        runBlocking { map.updatePolyline(states[1]) }
        overlay.show(map, states)
        assertEquals(listOf("native:road-1"), events)
        controller.destroy()
    }

    /** 地図ごとの指定（PolylineOverlay の tilingOptions）は、次に線を渡すときに効く */
    @Test
    fun perMapOptionsApplyOnTheNextHandOver() {
        val (controller, renderer, templates) = controller()
        val map = Driver(controller)
        val options = MutableStateFlow<PolylineTilingOptions?>(PolylineTilingOptions.Disabled)
        val overlay = PolylineOverlay(MutableStateFlow(mutableMapOf()), options)
        val states = roads(2000)

        overlay.show(map, states)
        assertEquals("Disabled: 全部ネイティブ", 2000, renderer.onMap.size)
        assertNull(templates.last())

        options.value = PolylineTilingOptions.Default
        overlay.show(map, states)
        assertTrue("Default: 全部タイル", renderer.onMap.isEmpty())
        assertNotNull(templates.last())
        assertEquals(PolylineTilingOptions.Default, controller.tiler?.options)
        controller.destroy()
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
        val s = sin(Math.toRadians(lat))
        val px = (lng + 180) / 360 * scale - x * 256
        val py = (0.5 - ln((1 + s) / (1 - s)) / (4 * Math.PI)) * scale - y * 256
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
        val s = sin(Math.toRadians(lat))
        val y = ((0.5 - ln((1 + s) / (1 - s)) / (4 * Math.PI)) * n).toInt()
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
