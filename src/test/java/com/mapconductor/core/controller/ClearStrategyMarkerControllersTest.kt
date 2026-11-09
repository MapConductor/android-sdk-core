package com.mapconductor.core.controller

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface
import com.mapconductor.core.map.VisibleRegion
import com.mapconductor.core.marker.MarkerEntity
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerRenderingStrategyInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.marker.StrategyMarkerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `clearOverlays()` が、拡張モジュールが [BaseMapViewController.registerOverlayController] で
 * 登録した [StrategyMarkerController]（クラスタリングなど）も消すこと。
 *
 * プロバイダの `clearOverlays()` は自分のフィールドのコントローラしか clear しておらず、
 * Strategy で描いたマーカーは clearOverlays() の後も地図に残っていた。
 */
class ClearStrategyMarkerControllersTest {
    private val bounds = GeoRectBounds(GeoPoint(34.0, 138.0), GeoPoint(36.0, 140.0))
    private val camera =
        MapCameraPosition(
            position = GeoPoint(35.0, 139.0),
            zoom = 10.0,
            visibleRegion = VisibleRegion(bounds, null, null, null, null),
        )

    /** GL 系の描画担当: onPostProcess で manager の全部を描き直す。 */
    private class GlRenderer(
        private val manager: MarkerManager<String>,
    ) : MarkerOverlayRendererInterface<String> {
        var drawn: List<String> = emptyList()
        override var animateStartListener: OnMarkerEventHandler? = null
        override var animateEndListener: OnMarkerEventHandler? = null
        override val holder: MapViewHolderInterface<*, *>
            get() = throw UnsupportedOperationException("not used")

        override suspend fun onAdd(data: List<MarkerOverlayRendererInterface.AddParamsInterface>) = data.map { it.state.id }

        override suspend fun onChange(data: List<MarkerOverlayRendererInterface.ChangeParamsInterface<String>>) =
            data.map { it.current.state.id }

        override suspend fun onRemove(data: List<MarkerEntityInterface<String>>) = Unit

        override suspend fun onAnimate(entity: MarkerEntityInterface<String>) = Unit

        override suspend fun onPostProcess() {
            drawn = manager.allEntities().map { it.state.id }.sorted()
        }
    }

    private class DrawAllStrategy : MarkerRenderingStrategyInterface<String> {
        override val markerManager: MarkerManager<String> = MarkerManager.defaultManager()

        override fun clear() = markerManager.clear()

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

    /** カメラだけ購読する拡張（android-heatmap のような）。clearOverlays で触られてはいけない。 */
    private class CameraOnlyController : OverlayControllerInterface<Unit, Unit> {
        var cleared = 0
        override val zIndex = 0

        override suspend fun add(data: List<Unit>) = Unit

        override suspend fun update(state: Unit) = Unit

        override suspend fun clear() {
            cleared++
        }

        override fun find(position: GeoPointInterface): Unit? = null

        override fun destroy() = Unit
    }

    /** プロバイダの clearOverlays() と同じ形: 自分のコントローラを消してから共通部分を呼ぶ。 */
    private class Driver : BaseMapViewController() {
        override val holder get() = throw UnsupportedOperationException()
        override val defaultCoroutine = CoroutineScope(Dispatchers.Unconfined)
        override val mainCoroutine = defaultCoroutine

        override fun moveCamera(position: MapCameraPosition) = Unit

        override fun animateCamera(
            position: MapCameraPosition,
            duration: Long,
        ) = Unit

        override fun fitBounds(
            bounds: GeoRectBounds,
            padding: Int,
        ) = Unit

        override suspend fun clearOverlays() {
            clearStrategyMarkerControllers()
        }
    }

    private fun markers() =
        listOf(
            MarkerState(position = GeoPoint(35.0, 139.0), id = "m1"),
            MarkerState(position = GeoPoint(35.1, 139.1), id = "m2"),
        )

    @Test
    fun `clearOverlays は登録された Strategy のマーカーを消し、他の登録物には触らない`() =
        runTest {
            val driver = Driver()
            val groups =
                (0..1).map {
                    val strategy = DrawAllStrategy()
                    val renderer = GlRenderer(strategy.markerManager)
                    val controller = StrategyMarkerController(strategy, renderer)
                    driver.registerOverlayController(controller)
                    controller.onCameraChanged(camera)
                    controller.add(markers())
                    Triple(strategy, renderer, controller)
                }
            val other = CameraOnlyController()
            driver.registerOverlayController(other)
            groups.forEach { assertEquals(listOf("m1", "m2"), it.second.drawn) }

            driver.clearOverlays()

            groups.forEach { (strategy, renderer, _) ->
                assertEquals(emptyList<String>(), renderer.drawn)
                assertEquals(0, strategy.markerManager.allEntities().size)
            }
            assertEquals(0, other.cleared)

            // 送り直せばまた描かれる。
            groups.forEach { it.third.add(markers()) }
            groups.forEach { assertEquals(listOf("m1", "m2"), it.second.drawn) }
        }
}
