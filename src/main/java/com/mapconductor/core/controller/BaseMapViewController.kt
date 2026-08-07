package com.mapconductor.core.controller

import com.mapconductor.core.OnCameraMoveHandler
import com.mapconductor.core.OnMapEventHandler
import com.mapconductor.core.OnMapInitializedHandler
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.CameraRestriction
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

abstract class BaseMapViewController : MapViewControllerInterface {
    abstract override val holder: MapViewHolderInterface<*, *>
    abstract val defaultCoroutine: CoroutineScope
    abstract val mainCoroutine: CoroutineScope
    private val overlayControllers = CopyOnWriteArrayList<OverlayControllerInterface<*, *>>()
    protected var cameraMoveStartCallback: OnCameraMoveHandler? = null
    protected var cameraMoveCallback: OnCameraMoveHandler? = null
    protected var cameraMoveEndCallback: OnCameraMoveHandler? = null
    protected var mapClickCallback: OnMapEventHandler? = null
    protected var mapLongClickCallback: OnMapEventHandler? = null

    protected var mapInitializedCallback: OnMapInitializedHandler? = null
    private var mapInitialized: Boolean = false
    private var mapInitializedCallbackDelivered: Boolean = false

    /**
     * ネイティブ SDK に直接のカメラ範囲制限 API を持たないプロバイダ（HERE/ArcGIS/TomTom）が
     * クランプ方式で使う制限設定。ネイティブ API を持つプロバイダ（Google/Mapbox/MapLibre）は
     * [setCameraRestriction] をオーバーライドして直接適用するため、この値は使わない。
     */
    private var cameraRestriction: CameraRestriction? = null

    override fun setCameraRestriction(restriction: CameraRestriction?) {
        cameraRestriction = restriction?.takeUnless { it.isEmpty }
    }

    protected fun hasCameraRestriction(): Boolean = cameraRestriction != null

    /**
     * カメラ位置が [setCameraRestriction] の制限に違反していれば補正後の位置を返す。違反が無ければ null。
     *
     * ズームは統一ズーム（Google 準拠）前提。範囲制限はネイティブの
     * `setLatLngBoundsForCameraTarget` 相当（カメラ中心を矩形内へクランプ）のセマンティクスに揃える。
     * クランプ方式のプロバイダはカメラ停止時にこれを呼び、返り値があれば [moveCamera] で再適用する。
     * ε を用いて微小な誤差では補正しないことで、再適用 → イベント → 再補正の無限ループを防ぐ。
     */
    protected fun cameraRestrictionCorrection(current: MapCameraPosition): MapCameraPosition? {
        val restriction = cameraRestriction ?: return null

        var lat = current.position.latitude
        var lng = current.position.longitude
        var zoom = current.zoom
        var changed = false

        restriction.minZoom?.let { min ->
            if (zoom < min - ZOOM_EPS) {
                zoom = min
                changed = true
            }
        }
        restriction.maxZoom?.let { max ->
            if (zoom > max + ZOOM_EPS) {
                zoom = max
                changed = true
            }
        }

        val bounds = restriction.bounds
        val sw = bounds?.southWest
        val ne = bounds?.northEast
        if (sw != null && ne != null) {
            val south = minOf(sw.latitude, ne.latitude)
            val north = maxOf(sw.latitude, ne.latitude)
            val west = minOf(sw.longitude, ne.longitude)
            val east = maxOf(sw.longitude, ne.longitude)
            val clampedLat = lat.coerceIn(south, north)
            val clampedLng = lng.coerceIn(west, east)
            if (abs(clampedLat - lat) > COORD_EPS) {
                lat = clampedLat
                changed = true
            }
            if (abs(clampedLng - lng) > COORD_EPS) {
                lng = clampedLng
                changed = true
            }
        }

        if (!changed) return null
        return current.copy(position = GeoPoint(lat, lng), zoom = zoom)
    }

    fun setCameraMoveStartListener(listener: OnCameraMoveHandler?) {
        this.cameraMoveStartCallback = listener
    }

    fun setCameraMoveListener(listener: OnCameraMoveHandler?) {
        this.cameraMoveCallback = listener
    }

    fun setCameraMoveEndListener(listener: OnCameraMoveHandler?) {
        this.cameraMoveEndCallback = listener
    }

    fun setMapClickListener(listener: OnMapEventHandler?) {
        this.mapClickCallback = listener
    }

    fun setMapLongClickListener(listener: OnMapEventHandler?) {
        this.mapLongClickCallback = listener
    }

    override fun registerOverlayController(controller: OverlayControllerInterface<*, *>) {
        if (overlayControllers.contains(controller)) return
        overlayControllers.add(controller)
    }

    fun setMapInitializedListener(listener: OnMapInitializedHandler?) {
        this.mapInitializedCallback = listener
        deliverMapInitializedCallbackIfReady()
    }

    /**
     * Records map initialization and delivers it once the listener is available.
     *
     * Some SDK controllers finish initialization while their Compose host is still
     * installing callbacks. Keeping this notification sticky prevents a completed
     * initialization from being lost in that window.
     */
    protected fun notifyMapInitialized() {
        mapInitialized = true
        deliverMapInitializedCallbackIfReady()
    }

    private fun deliverMapInitializedCallbackIfReady() {
        if (!mapInitialized || mapInitializedCallbackDelivered) return
        val callback = mapInitializedCallback ?: return
        mapInitializedCallbackDelivered = true
        callback.invoke()
    }

    protected suspend fun notifyMapCameraPosition(mapCameraPosition: MapCameraPosition) {
        overlayControllers.forEach {
            if (it is OnCameraChangeReceiverInterface) {
                it.onCameraChanged(mapCameraPosition)
            }
        }
        cameraMoveCallback?.let { callBack ->
            callBack(mapCameraPosition)
        }
    }

    override fun destroy() {
        overlayControllers.forEach { it.destroy() }
        overlayControllers.clear()
        // Stop camera/click listener jobs still running on this controller's
        // scope; without this the scope (and everything its jobs capture)
        // outlives the map.
        defaultCoroutine.cancel()
    }

    private companion object {
        // 統一ズーム（Google 準拠）での許容誤差。これ未満の差では補正しない。
        const val ZOOM_EPS = 1e-3

        // 緯度経度（度）での許容誤差。
        const val COORD_EPS = 1e-7
    }
}
