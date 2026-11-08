package com.mapconductor.core.marker

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.controller.OnCameraChangeReceiverInterface
import com.mapconductor.core.controller.OverlayHit
import com.mapconductor.core.controller.OverlayKind
import com.mapconductor.core.controller.SlottedOverlayController
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class StrategyMarkerController<ActualMarker>(
    private val strategy: MarkerRenderingStrategyInterface<ActualMarker>,
    override val renderer: MarkerOverlayRendererInterface<ActualMarker>,
) : SlottedOverlayController<
        MarkerState,
        MarkerEntityInterface<ActualMarker>,
    >,
    OnCameraChangeReceiverInterface,
    MarkerEventHostInterface<ActualMarker> {
    override val markerManager: MarkerManager<ActualMarker> = strategy.markerManager
    override val zIndex: Int = 10
    private var mapCameraPosition: MapCameraPosition? = null
    private var lastKnownBounds: GeoRectBounds? = null
    private val semaphore = Semaphore(1)
    private var pendingStates: List<MarkerState>? = null

    override var dragStartListener: OnMarkerEventHandler? = null
    override var dragListener: OnMarkerEventHandler? = null
    override var dragEndListener: OnMarkerEventHandler? = null
    override var animateStartListener: OnMarkerEventHandler? = null
    override var animateEndListener: OnMarkerEventHandler? = null
    override var clickListener: OnMarkerEventHandler? = null

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
    override fun dispatchClick(state: MarkerState) {
        if (!state.clickable) return
        state.onClick?.invoke(state)
        clickListener?.invoke(state)
    }

    override fun dispatchDragStart(state: MarkerState) {
        state.onDragStart?.invoke(state)
        dragStartListener?.invoke(state)
    }

    override fun dispatchDrag(state: MarkerState) {
        state.onDrag?.invoke(state)
        dragListener?.invoke(state)
    }

    override fun dispatchDragEnd(state: MarkerState) {
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

    /**
     * 他の 5 種類・[AbstractMarkerController] の clear() と同じく
     * 「onRemove(全部) → 空にする → onPostProcess」の順に呼ぶ。
     *
     * 以前は strategy.clear() で manager を空にするだけだった。GL 系の描画担当は
     * onRemove では地図に触らず onPostProcess で manager から描き直すので、
     * 呼ばないと clear() の後もマーカーが地図に残る。onRemove はアイコン等の
     * 描画側の資源を返すため。空にするのは strategy.clear() のままにして、
     * ストラテジ固有の後片付け（クラスタの状態など）を保つ。
     * add / update と同じ permit の中で行い、ストラテジの処理と交錯させない。
     */
    override suspend fun clear() {
        semaphore.withPermit {
            val entities = markerManager.allEntities()
            if (entities.isNotEmpty()) renderer.onRemove(entities)
            strategy.clear()
            // 最初のカメラ待ちで保留していたものも消す。残すと次のカメラで描かれてしまう。
            pendingStates = null
            renderer.onPostProcess()
        }
    }

    override fun getEntity(id: String): MarkerEntityInterface<ActualMarker>? = strategy.markerManager.getEntity(id)

    @OptIn(InternalMapConductorApi::class)
    override fun find(position: GeoPointInterface): MarkerEntityInterface<ActualMarker>? {
        val holder = renderer.holder
        val touchScreen = holder.toScreenOffset(position) ?: return null
        val bounds = MarkerHitPick.searchBounds(position, touchScreen, holder::toScreenOffset) ?: return null
        return MarkerHitPick.pick(touchScreen, strategy.markerManager.findMarkersInBounds(bounds), holder::toScreenOffset)
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

    override val kind: OverlayKind = OverlayKind.Marker

    /** マーカーは別経路（[com.mapconductor.core.controller.BaseMapViewController.dispatchMarkerTap]）。 */
    override fun resolveTap(position: GeoPointInterface): OverlayHit? = null

    override fun has(id: String): Boolean = markerManager.hasEntity(id)
}
