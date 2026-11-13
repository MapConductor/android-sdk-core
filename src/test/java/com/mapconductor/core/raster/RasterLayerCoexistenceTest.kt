package com.mapconductor.core.raster

import com.mapconductor.core.circle.circleTileRasterLayerState
import com.mapconductor.core.polygon.polygonTileRasterLayerState
import com.mapconductor.core.polyline.polylineTileRasterLayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1 枚の地図に載るラスターレイヤーは 5 系統ある。アプリの RasterLayer（add = composition）、
 * マーカータイル（marker-tile-*）、ポリゴンタイル（mc-polygon-tiles）、ポリラインタイル（mc-polyline-tiles）、
 * 円のタイル（mc-circle-tiles）。
 *
 * js-sdk-core の `RasterLayerCoexistence.test.mjs`、ios-sdk の `RasterLayerCoexistenceTests` と
 * 1 対 1 で対応する。
 *
 * ## なぜここを固定するか
 *
 * マーカータイルを composition / clear で載せ外ししていたプロバイダでは、composition が
 * 「渡されなかったレイヤーを消す」ので、マーカータイルを載せるとアプリの RasterLayer が、
 * アプリの RasterLayer が変わるとマーカータイルが消え、どちらも自分のデータが次に変わるまで
 * 戻らなかった（web で起きた）。SDK が自分で載せるレイヤーは upsert / removeById で扱い、
 * add はアプリのレイヤーだけを入れ替える。
 */
class RasterLayerCoexistenceTest {
    private class Renderer(
        val onMap: MutableSet<String>,
    ) : RasterLayerOverlayRendererInterface<String> {
        override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

        override suspend fun onAdd(data: List<RasterLayerOverlayRendererInterface.AddParamsInterface>): List<String?> =
            data.map { it.state.id.also(onMap::add) }

        override suspend fun onChange(data: List<RasterLayerOverlayRendererInterface.ChangeParamsInterface<String>>): List<String?> =
            data.map { it.current.state.id }

        override suspend fun onRemove(data: List<RasterLayerEntityInterface<String>>) {
            data.forEach { onMap.remove(it.state.id) }
        }

        override suspend fun onPostProcess() {}
    }

    private class Controller(
        renderer: Renderer,
    ) : RasterLayerController<String>(RasterLayerManager(), renderer)

    private fun layer(id: String) =
        RasterLayerState(
            id = id,
            source = RasterLayerSource.UrlTemplate(template = "https://example.com/$id/{z}/{x}/{y}.png"),
        )

    private fun markerTiles(version: Int) =
        RasterLayerState(
            id = "marker-tile-m",
            source = RasterLayerSource.UrlTemplate(template = "http://127.0.0.1/tiles/m/256/$version/{z}/{x}/{y}.png"),
        )

    @Test
    fun `アプリの RasterLayer・マーカータイル・ポリゴンタイル・ポリラインタイル・円のタイルは互いを消さない`() =
        runBlocking {
            val onMap = mutableSetOf<String>()
            val controller = Controller(Renderer(onMap))
            controller.add(listOf(layer("relief")))
            controller.upsert(polygonTileRasterLayerState("http://127.0.0.1/tiles/p/256/1/{z}/{x}/{y}.png"))
            controller.upsert(circleTileRasterLayerState("http://127.0.0.1/tiles/c/256/1/{z}/{x}/{y}.png"))
            controller.upsert(polylineTileRasterLayerState("http://127.0.0.1/tiles/l/256/1/{z}/{x}/{y}.png"))
            controller.upsert(markerTiles(1))
            val sdk = listOf("marker-tile-m", "mc-circle-tiles", "mc-polygon-tiles", "mc-polyline-tiles")
            assertEquals(sdk + "relief", onMap.sorted())

            // アプリの RasterLayer が変わっても、SDK のタイルは残る
            controller.add(listOf(layer("photo")))
            assertEquals(sdk + "photo", onMap.sorted())

            // マーカータイルの版が変わっても、ほかは残る
            controller.upsert(markerTiles(2))
            assertEquals(sdk + "photo", onMap.sorted())

            // ポリラインタイル・円のタイルの版が変わっても、ほかは残る
            controller.upsert(polylineTileRasterLayerState("http://127.0.0.1/tiles/l/256/2/{z}/{x}/{y}.png"))
            controller.upsert(circleTileRasterLayerState("http://127.0.0.1/tiles/c/256/2/{z}/{x}/{y}.png"))
            assertEquals(sdk + "photo", onMap.sorted())

            // マーカータイルとポリラインタイルを外しても、ほかは残る
            controller.removeById("marker-tile-m")
            controller.removeById("mc-polyline-tiles")
            assertEquals(listOf("mc-circle-tiles", "mc-polygon-tiles", "photo"), onMap.sorted())

            // 円のタイルを外しても、ほかは残る
            controller.removeById("mc-circle-tiles")
            assertEquals(listOf("mc-polygon-tiles", "photo"), onMap.sorted())
        }
}
