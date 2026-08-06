package com.mapconductor.core.controller

import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.CameraRestriction
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface

interface MapViewControllerInterface {
    /*
     * ┌──────────────────────────────────────────────────────────────────────┐
     * │ getCameraPosition() / getBounds() をここに足さないこと。              │
     * │ DO NOT add a camera getter to this interface.                        │
     * └──────────────────────────────────────────────────────────────────────┘
     *
     * 解説ページ: /docs/reading-camera
     *
     * 1. 宣言的 UI（Compose）では状態の出どころを 1 つにする。カメラは状態なので
     *    置き場所は MapViewState。ここに getter を足すと state と SDK 直読みの
     *    2 系統になり、ズレる余地だけが増える。
     *
     * 2. push 型で設計されている。地図 SDK のカメライベント → プロバイダが生値を
     *    統一ズームへ変換し visibleRegion を載せる → MapViewState へ反映 →
     *    mapViewState.cameraPosition / onCameraMove / onCameraMoveEnd /
     *    登録済みオーバーレイの onCameraChanged。取りに行く必要がない。
     *
     * 3. pull は安くない。1 回の呼び出しで画面 4 隅の逆投影をして visibleRegion を
     *    組み立てる。単なるゲッターではない。
     *
     * 4. react-sdk には一時期これがあり、2026-08-06 に外した。実測では
     *    1 ドラッグあたり 86 回 → 30 回（約 65% 減）。減った分はすべて
     *    「直前に push したのと同じ値の作り直し」で、操作中だけ効くコストだった。
     *
     * 5. state は「最後に push された値」なので理屈のうえでは 1 フレーム古いが、
     *    onCameraMove は移動中も発火するため体感差はない。
     *
     * 生値がどうしても要る場合は [holder] からネイティブの地図を取ること。
     * 各プロバイダは private な getMapCameraPosition() を持っているが、それは
     * カメライベントに載せる値を組み立てるための実装であり、公開しない。
     */
    val holder: MapViewHolderInterface<*, *>

    suspend fun clearOverlays()

    fun moveCamera(position: MapCameraPosition)

    /**
     * カメラの可動範囲（パン範囲・ズーム上下限）を制限する。
     *
     * - Google / Mapbox / MapLibre : ネイティブの範囲制限 API（`setLatLngBoundsForCameraTarget` /
     *   `setBounds` / `setMin/MaxZoomPreference`）を用いてスムーズに制限する。
     * - HERE / ArcGIS / TomTom : ネイティブに直接の範囲制限 API が無いため、カメラ停止時に
     *   中心座標・ズームを矩形内へクランプして再適用する方式で制限する。
     *
     * ズームは統一ズーム（Google 準拠）で指定し、各プロバイダが自身の体系へ変換する。
     * null または空の [CameraRestriction] で制限解除。デフォルト実装は何もしない。
     */
    fun setCameraRestriction(restriction: CameraRestriction?) {}

    /**
     * Applies the gesture flags in [MapUISettings]. Providers whose map SDK can
     * toggle gestures override this; the default is a no-op.
     */
    fun applyUISettings(settings: com.mapconductor.core.map.MapUISettings) {}

    fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    )

    fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    )

    fun registerOverlayController(controller: OverlayControllerInterface<*, *>)

    fun getControllers(): Map<String, OverlayControllerInterface<*, *>>

    fun destroy()
}
