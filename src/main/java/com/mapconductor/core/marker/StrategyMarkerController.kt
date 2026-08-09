package com.mapconductor.core.marker

import com.mapconductor.core.controller.OnCameraChangeReceiverInterface
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class StrategyMarkerController<ActualMarker>(
    private val strategy: MarkerRenderingStrategyInterface<ActualMarker>,
    private val renderer: MarkerOverlayRendererInterface<ActualMarker>,
) : OverlayControllerInterface<
        MarkerState,
        MarkerEntityInterface<ActualMarker>,
    >,
    OnCameraChangeReceiverInterface {
    val markerManager: MarkerManager<ActualMarker> = strategy.markerManager
    override val zIndex: Int = 10
    private var mapCameraPosition: MapCameraPosition? = null
    private var lastKnownBounds: GeoRectBounds? = null
    private val semaphore = Semaphore(1)
    private var pendingStates: List<MarkerState>? = null

    var dragStartListener: OnMarkerEventHandler? = null
    var dragListener: OnMarkerEventHandler? = null
    var dragEndListener: OnMarkerEventHandler? = null
    var animateStartListener: OnMarkerEventHandler? = null
    var animateEndListener: OnMarkerEventHandler? = null
    var clickListener: OnMarkerEventHandler? = null

    init {
        renderer.animateStartListener = { state -> dispatchAnimateStart(state) }
        renderer.animateEndListener = { state -> dispatchAnimateEnd(state) }
    }

    /**
     * クリックを配送する。
     *
     * `clickable=false` のマーカーには配送しない。マーカーのヒットテスト
     * （[find]）はドラッグの開始判定にも使われるため、そちらでは `clickable` を
     * 見られない（`clickable=false` かつ `draggable=true` のマーカーがドラッグ
     * 不能になってしまう）。判定をここに置くことで、ドラッグを保ったまま
     * どのプロバイダでも同じ挙動になる。
     */
    fun dispatchClick(state: MarkerState) {
        if (!state.clickable) return
        state.onClick?.invoke(state)
        clickListener?.invoke(state)
    }

    fun dispatchDragStart(state: MarkerState) {
        state.onDragStart?.invoke(state)
        dragStartListener?.invoke(state)
    }

    fun dispatchDrag(state: MarkerState) {
        state.onDrag?.invoke(state)
        dragListener?.invoke(state)
    }

    fun dispatchDragEnd(state: MarkerState) {
        state.onDragEnd?.invoke(state)
        dragEndListener?.invoke(state)
    }

    fun dispatchAnimateStart(state: MarkerState) {
        state.onAnimateStart?.invoke(state)
        animateStartListener?.invoke(state)
    }

    fun dispatchAnimateEnd(state: MarkerState) {
        state.onAnimateEnd?.invoke(state)
        animateEndListener?.invoke(state)
    }

    override suspend fun add(data: List<MarkerState>) {
        val bounds = mapCameraPosition?.visibleRegion?.bounds ?: lastKnownBounds
        if (bounds == null) {
            pendingStates = data
            return
        }
        semaphore.withPermit {
            strategy.onAdd(
                data = data,
                viewport = bounds,
                renderer = renderer,
            )
        }
    }

    override suspend fun update(state: MarkerState) {
        val bounds = mapCameraPosition?.visibleRegion?.bounds ?: lastKnownBounds ?: return
        semaphore.withPermit {
            strategy.onUpdate(
                state = state,
                viewport = bounds,
                renderer = renderer,
            )
        }
    }

    override suspend fun clear() {
        strategy.clear()
    }

    fun getEntity(id: String): MarkerEntityInterface<ActualMarker>? = strategy.markerManager.getEntity(id)

    override fun find(position: GeoPointInterface): MarkerEntityInterface<ActualMarker>? {
        val nearest = strategy.markerManager.findNearest(position) ?: return null
        val touchScreen = renderer.holder.toScreenOffset(position) ?: return null
        val markerScreen = renderer.holder.toScreenOffset(nearest.state.position) ?: return null

        return if (MarkerHitTest.hitsIcon(touchScreen, markerScreen, nearest.state)) {
            nearest
        } else {
            null
        }
    }

    override suspend fun onCameraChanged(mapCameraPosition: MapCameraPosition) {
        this.mapCameraPosition = mapCameraPosition
        mapCameraPosition.visibleRegion?.bounds?.let { lastKnownBounds = it }
        semaphore.withPermit {
            strategy.onCameraChanged(mapCameraPosition, renderer)
        }
        val pending = pendingStates ?: return
        pendingStates = null
        add(pending)
    }

    override fun destroy() {
        strategy.clear()
    }
}
