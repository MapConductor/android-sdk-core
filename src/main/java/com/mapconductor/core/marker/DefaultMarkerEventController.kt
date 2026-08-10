package com.mapconductor.core.marker

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPointInterface

/**
 * マーカーのイベント配送に必要な面。[AbstractMarkerController] と
 * [StrategyMarkerController] の共通部分。
 *
 * この 2 つは継承関係が無い（前者はネイティブマーカーを自前で管理し、後者は
 * 描画戦略へ委譲する）が、イベント配送から見ると同じものである。
 * [DefaultMarkerEventController] が両方を同じコードで扱えるようにするための面。
 */
@InternalMapConductorApi
interface MarkerEventHostInterface<ActualMarker> {
    val markerManager: MarkerManager<ActualMarker>
    val renderer: MarkerOverlayRendererInterface<ActualMarker>

    var clickListener: OnMarkerEventHandler?
    var dragStartListener: OnMarkerEventHandler?
    var dragListener: OnMarkerEventHandler?
    var dragEndListener: OnMarkerEventHandler?
    var animateStartListener: OnMarkerEventHandler?
    var animateEndListener: OnMarkerEventHandler?

    fun find(position: GeoPointInterface): MarkerEntityInterface<ActualMarker>?

    fun getEntity(id: String): MarkerEntityInterface<ActualMarker>?

    fun dispatchClick(state: MarkerState)

    fun dispatchDragStart(state: MarkerState)

    fun dispatchDrag(state: MarkerState)

    fun dispatchDragEnd(state: MarkerState)
}

/**
 * マーカーのイベント配送。プロバイダが各自持っていた薄いラッパーの集約。
 *
 * android-for-maplibre / mapbox / here / googlemaps / tomtom / arcgis が
 * `DefaultXxxMarkerEventController` と `StrategyXxxMarkerEventController` を
 * 2 本ずつ（1 プロバイダ 120〜200 行）持っており、中身は型名以外まったく同じだった。
 *
 * ## 何をするクラスか
 *
 * ジェスチャ処理（ドライバー側）とマーカーコントローラ（コア側）の間に立って、
 *  - タップの引き当てと配送
 *  - ドラッグの開始・移動・終了の配送
 *  - ドラッグ中のマーカーの保持
 *  - アプリが設定した非推奨リスナーの転送
 * を行う。**ネイティブ SDK に一切触らない**ので、プロバイダごとに書く必要が無い。
 *
 * ## ドラッグ層はレンダラが持つ
 *
 * MapLibre / Mapbox は、ドラッグ中のマーカーを通常のレイヤから外して専用の
 * 「ドラッグ層」へ移す。これはネイティブの描画に踏み込む話なので、
 * [MarkerOverlayRendererInterface.onDragSelectionChanged] /
 * [MarkerOverlayRendererInterface.onDragPositionChanged] としてレンダラに委ねる。
 * 既定は何もしないので、ドラッグ層を持たないプロバイダ（HERE は
 * ネイティブのマーカーをそのまま動かす）は何も実装しなくてよい。
 *
 * ## ネイティブのマーカークリックにも対応する
 *
 * Google Maps / TomTom はネイティブのマーカークリックリスナーを使わざるを得ない
 * （[dispatchNativeMarkerClick] を参照）ので [NativeMarkerClickTargetInterface] も
 * 実装しておく。使わないプロバイダに害は無い。
 */
@InternalMapConductorApi
open class DefaultMarkerEventController<ActualMarker>(
    private val host: MarkerEventHostInterface<ActualMarker>,
) : MarkerEventControllerInterface<ActualMarker>,
    GeoMarkerClickTargetInterface<ActualMarker>,
    NativeMarkerClickTargetInterface<ActualMarker> {
    /** ドラッグ中のマーカー。ドラッグしていなければ null。 */
    private var selected: MarkerEntityInterface<ActualMarker>? = null

    /** 描画の実体。レイヤの追加などプロバイダ固有の配線から参照する。 */
    val renderer: MarkerOverlayRendererInterface<ActualMarker>
        get() = host.renderer

    override fun find(position: GeoPointInterface): MarkerEntityInterface<ActualMarker>? = host.find(position)

    override fun getEntity(id: String): MarkerEntityInterface<ActualMarker>? = host.getEntity(id)

    override fun dispatchClick(state: MarkerState) = host.dispatchClick(state)

    fun dispatchDragStart(state: MarkerState) = host.dispatchDragStart(state)

    fun dispatchDrag(state: MarkerState) = host.dispatchDrag(state)

    fun dispatchDragEnd(state: MarkerState) = host.dispatchDragEnd(state)

    fun getSelectedMarker(): MarkerEntityInterface<ActualMarker>? = selected

    /**
     * ドラッグ対象を設定する。null で解除。
     *
     * 通常レイヤとドラッグ層の入れ替えはレンダラに委ねる（[renderer] の
     * [MarkerOverlayRendererInterface.onDragSelectionChanged]）。
     */
    fun setSelectedMarker(entity: MarkerEntityInterface<ActualMarker>?) {
        val previous = selected
        selected = entity
        host.renderer.onDragSelectionChanged(previous = previous, current = entity)
    }

    /**
     * ドラッグ中のマーカーの位置が動いた。
     *
     * ジェスチャ側は `state.position` を書き換えたうえでこれを呼ぶ。
     * ドラッグ層を持つプロバイダはここで層を描き直す。
     */
    fun updateDragPosition(position: GeoPointInterface) {
        host.renderer.onDragPositionChanged(position)
    }

    fun setClickListener(listener: OnMarkerEventHandler?) {
        host.clickListener = listener
    }

    fun setDragStartListener(listener: OnMarkerEventHandler?) {
        host.dragStartListener = listener
    }

    fun setDragListener(listener: OnMarkerEventHandler?) {
        host.dragListener = listener
    }

    fun setDragEndListener(listener: OnMarkerEventHandler?) {
        host.dragEndListener = listener
    }

    fun setAnimateStartListener(listener: OnMarkerEventHandler?) {
        host.animateStartListener = listener
    }

    fun setAnimateEndListener(listener: OnMarkerEventHandler?) {
        host.animateEndListener = listener
    }
}
