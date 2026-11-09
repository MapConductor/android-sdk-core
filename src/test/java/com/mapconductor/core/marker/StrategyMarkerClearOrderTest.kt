package com.mapconductor.core.marker

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface
import com.mapconductor.core.map.VisibleRegion
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [StrategyMarkerController.clear] は他のコントローラの clear() と同じく
 * 「onRemove(全部) → 空にする → onPostProcess」で、何も描かれていない状態で終わること。
 *
 * 以前は strategy.clear() で manager を空にするだけで、onRemove も onPostProcess も
 * 呼ばなかった。GL 系の描画担当は onPostProcess で manager から描き直すので、
 * clear() の後もマーカーが地図に残る。js-sdk-core / ios-sdk-core の同名テストと同じ。
 */
class StrategyMarkerClearOrderTest {
    private val bounds = GeoRectBounds(GeoPoint(34.0, 138.0), GeoPoint(36.0, 140.0))
    private val camera =
        MapCameraPosition(
            position = GeoPoint(35.0, 139.0),
            zoom = 10.0,
            visibleRegion = VisibleRegion(bounds, null, null, null, null),
        )

    /** GL 系の描画担当: onRemove では何もせず、onPostProcess で manager の全部を描き直す。 */
    private class GlRenderer(
        private val manager: MarkerManager<String>,
    ) : MarkerOverlayRendererInterface<String> {
        var drawn: List<String> = emptyList()
        val removed = mutableListOf<String>()
        override var animateStartListener: OnMarkerEventHandler? = null
        override var animateEndListener: OnMarkerEventHandler? = null
        override val holder: MapViewHolderInterface<*, *>
            get() = throw UnsupportedOperationException("not used")

        override suspend fun onAdd(data: List<MarkerOverlayRendererInterface.AddParamsInterface>) = data.map { it.state.id }

        override suspend fun onChange(data: List<MarkerOverlayRendererInterface.ChangeParamsInterface<String>>) =
            data.map { it.current.state.id }

        override suspend fun onRemove(data: List<MarkerEntityInterface<String>>) {
            removed += data.map { it.state.id }
        }

        override suspend fun onAnimate(entity: MarkerEntityInterface<String>) = Unit

        override suspend fun onPostProcess() {
            drawn = manager.allEntities().map { it.state.id }.sorted()
        }
    }

    /**
     * 渡されたマーカーを全部描くストラテジ。既定アイコン（AbstractMarkerRenderingStrategy が持つ）は
     * Android の Bitmap を焼くので素の JVM では使えない。描いた状態は manager へ直接登録する。
     */
    private class DrawAllStrategy : MarkerRenderingStrategyInterface<String> {
        override val markerManager: MarkerManager<String> = MarkerManager.defaultManager()
        var cleared = 0

        override fun clear() {
            cleared++
            markerManager.clear()
        }

        override suspend fun onUpdate(
            state: MarkerState,
            viewport: GeoRectBounds,
            renderer: MarkerOverlayRendererInterface<String>,
        ): Boolean = true

        override suspend fun onAdd(
            data: List<MarkerState>,
            viewport: GeoRectBounds,
            renderer: MarkerOverlayRendererInterface<String>,
        ): Boolean {
            data.forEach { markerManager.registerEntity(MarkerEntity(marker = it.id, state = it, isRendered = true)) }
            renderer.onPostProcess()
            return true
        }

        override suspend fun onCameraChanged(
            cameraPosition: MapCameraPosition,
            renderer: MarkerOverlayRendererInterface<String>,
        ) = Unit
    }

    private fun markers() =
        listOf(
            MarkerState(position = GeoPoint(35.0, 139.0), id = "m1"),
            MarkerState(position = GeoPoint(35.1, 139.1), id = "m2"),
        )

    @Test
    fun `clear 後は何も描かれず、ストラテジ自身の後片付けも走る`() =
        runTest {
            val strategy = DrawAllStrategy()
            val renderer = GlRenderer(strategy.markerManager)
            val controller = StrategyMarkerController(strategy, renderer)
            controller.onCameraChanged(camera)
            controller.add(markers())
            assertEquals(listOf("m1", "m2"), renderer.drawn)

            controller.clear()

            assertEquals(listOf("m1", "m2"), renderer.removed.sorted())
            assertEquals(1, strategy.cleared)
            assertEquals(emptyList<String>(), renderer.drawn)
        }

    @Test
    fun `最初のカメラ待ちのマーカーも clear で捨てる`() =
        runTest {
            val strategy = DrawAllStrategy()
            val renderer = GlRenderer(strategy.markerManager)
            val controller = StrategyMarkerController(strategy, renderer)
            controller.add(markers()) // カメラ前なので保留
            controller.clear()
            controller.onCameraChanged(camera)
            assertEquals(emptyList<String>(), renderer.drawn)
        }

    @Test
    fun `clear 後に送り直せばまた描かれる`() =
        runTest {
            val strategy = DrawAllStrategy()
            val renderer = GlRenderer(strategy.markerManager)
            val controller = StrategyMarkerController(strategy, renderer)
            controller.onCameraChanged(camera)
            controller.add(markers())
            controller.clear()
            controller.add(markers())
            assertEquals(listOf("m1", "m2"), renderer.drawn)
        }
}
