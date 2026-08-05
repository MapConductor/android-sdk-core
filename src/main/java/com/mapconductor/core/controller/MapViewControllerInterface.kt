package com.mapconductor.core.controller

import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.CameraRestriction
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface

interface MapViewControllerInterface {
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
