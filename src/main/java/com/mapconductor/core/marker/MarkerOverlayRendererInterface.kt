package com.mapconductor.core.marker

import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapViewHolderInterface

interface MarkerOverlayRendererInterface<ActualMarker> {
    var animateStartListener: OnMarkerEventHandler?
    var animateEndListener: OnMarkerEventHandler?

    val holder: MapViewHolderInterface<*, *>

    interface AddParamsInterface {
        val state: MarkerState
        val bitmapIcon: BitmapIcon
    }

    interface ChangeParamsInterface<ActualMarker> {
        val current: MarkerEntityInterface<ActualMarker>
        val bitmapIcon: BitmapIcon
        val prev: MarkerEntityInterface<ActualMarker>
    }

    suspend fun onAdd(data: List<AddParamsInterface>): List<ActualMarker?>

    suspend fun onChange(data: List<ChangeParamsInterface<ActualMarker>>): List<ActualMarker?>

    suspend fun onRemove(data: List<MarkerEntityInterface<ActualMarker>>)

    suspend fun onAnimate(entity: MarkerEntityInterface<ActualMarker>)

    suspend fun onPostProcess()

    /**
     * ドラッグ対象が変わった。[current] が null なら解除。
     *
     * ドラッグ中のマーカーを通常のレイヤから外して専用の層へ移すプロバイダ
     * （MapLibre / Mapbox）が実装する。**既定は何もしない**ので、ネイティブの
     * マーカーをそのまま動かすプロバイダ（HERE など）は実装しなくてよい。
     *
     * マネージャからの出し入れもここで行う。通常レイヤの再描画と層の入れ替えの
     * 順序はプロバイダの描画方式に依存するため、ひとまとまりで渡している。
     */
    fun onDragSelectionChanged(
        previous: MarkerEntityInterface<ActualMarker>?,
        current: MarkerEntityInterface<ActualMarker>?,
    ) = Unit

    /**
     * ドラッグ中のマーカーの位置が動いた。既定は何もしない。
     *
     * ドラッグ層を持つプロバイダがここで層を描き直す。
     */
    fun onDragPositionChanged(position: GeoPointInterface) = Unit
}
