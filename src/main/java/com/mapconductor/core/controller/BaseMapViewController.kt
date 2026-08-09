package com.mapconductor.core.controller

import com.mapconductor.core.OnCameraMoveHandler
import com.mapconductor.core.OnMapEventHandler
import com.mapconductor.core.OnMapInitializedHandler
import com.mapconductor.core.circle.CircleCapableInterface
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.groundimage.GroundImageCapableInterface
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.OnGroundImageEventHandler
import com.mapconductor.core.marker.MarkerAnimationOverlayHost
import com.mapconductor.core.marker.MarkerCapableInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.polygon.OnPolygonEventHandler
import com.mapconductor.core.polygon.PolygonCapableInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.OnPolylineEventHandler
import com.mapconductor.core.polyline.PolylineCapableInterface
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.raster.RasterLayerCapableInterface
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.map.CameraRestriction
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

abstract class BaseMapViewController :
    MapViewControllerInterface,
    MarkerCapableInterface,
    PolylineCapableInterface,
    PolygonCapableInterface,
    CircleCapableInterface,
    GroundImageCapableInterface,
    RasterLayerCapableInterface {
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

    // ── Capable ファサードの既定実装 ────────────────────────────────────
    //
    // 各プロバイダの *ViewController が `compositionXxx` / `updateXxx` / `hasXxx` を
    // 「登録済みコントローラへ 1 行転送するだけ」で 8 プロバイダ x 約 100 行あった。
    // 登録済みの [OverlayControllerInterface] から型で解決して既定実装にする。
    //
    // 描画前に追加処理が要るプロバイダ（MapLibre / Mapbox の polygon は
    // z レイヤの再構築が要る）は override して super を呼ぶ。

    /**
     * この種別の**主**コントローラ（最初に登録されたもの）。
     *
     * `compositionXxx` / `updateXxx` はここへ流す。クラスタリングは同じ Marker 種別で
     * 追加のコントローラを登録するが、composition の受け口は最初の 1 つでよい
     * （追加分はクラスタリング側が自分で駆動する）。
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <StateType : Any> primaryOverlayController(
        kind: OverlayKind,
    ): OverlayControllerInterface<StateType, *>? =
        overlayControllers.firstOrNull { it.kind == kind } as? OverlayControllerInterface<StateType, *>

    /** この種別に登録されたすべてのコントローラ。`hasXxx` は「どれかが持っていれば true」。 */
    protected fun overlayControllersOf(kind: OverlayKind): List<OverlayControllerInterface<*, *>> =
        overlayControllers.filter { it.kind == kind }

    /** [kind] のいずれかのコントローラがこの id を持っているか。`hasXxx` の既定実装。 */
    protected fun hasOverlay(
        kind: OverlayKind,
        id: String,
    ): Boolean = overlayControllersOf(kind).any { it.has(id) }

    // Marker
    override suspend fun compositionMarkers(data: List<MarkerState>) {
        primaryOverlayController<MarkerState>(OverlayKind.Marker)?.add(data)
    }

    override suspend fun updateMarker(state: MarkerState) {
        primaryOverlayController<MarkerState>(OverlayKind.Marker)?.update(state)
    }

    override fun hasMarker(state: MarkerState): Boolean = hasOverlay(OverlayKind.Marker, state.id)

    // マーカーのリスナーとアニメーション層はプロバイダごとに配線先が違う
    // （markerEventControllers、レンダラのドラッグ層）ので既定は何もしない。
    @Deprecated("Use MarkerState.onDragStart instead.")
    override fun setOnMarkerDragStart(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onDrag instead.")
    override fun setOnMarkerDrag(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onDragEnd instead.")
    override fun setOnMarkerDragEnd(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onAnimateStart instead.")
    override fun setOnMarkerAnimateStart(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onAnimateEnd instead.")
    override fun setOnMarkerAnimateEnd(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onClick instead.")
    override fun setOnMarkerClickListener(listener: OnMarkerEventHandler?) = Unit

    override fun setMarkerAnimationOverlayHost(host: MarkerAnimationOverlayHost?) = Unit

    // Polyline
    override suspend fun compositionPolylines(data: List<PolylineState>) {
        primaryOverlayController<PolylineState>(OverlayKind.Polyline)?.add(data)
    }

    override suspend fun updatePolyline(state: PolylineState) {
        primaryOverlayController<PolylineState>(OverlayKind.Polyline)?.update(state)
    }

    override fun hasPolyline(state: PolylineState): Boolean = hasOverlay(OverlayKind.Polyline, state.id)

    @Deprecated("Use PolylineState.onClick instead.")
    override fun setOnPolylineClickListener(listener: OnPolylineEventHandler?) {
        overlayControllersOf(OverlayKind.Polyline).forEach { it.setClickListenerAny(listener) }
    }

    // Polygon
    override suspend fun compositionPolygons(data: List<PolygonState>) {
        primaryOverlayController<PolygonState>(OverlayKind.Polygon)?.add(data)
    }

    override suspend fun updatePolygon(state: PolygonState) {
        primaryOverlayController<PolygonState>(OverlayKind.Polygon)?.update(state)
    }

    override fun hasPolygon(state: PolygonState): Boolean = hasOverlay(OverlayKind.Polygon, state.id)

    @Deprecated("Use PolygonState.onClick instead.")
    override fun setOnPolygonClickListener(listener: OnPolygonEventHandler?) {
        overlayControllersOf(OverlayKind.Polygon).forEach { it.setClickListenerAny(listener) }
    }

    // Circle
    override suspend fun compositionCircles(data: List<CircleState>) {
        primaryOverlayController<CircleState>(OverlayKind.Circle)?.add(data)
    }

    override suspend fun updateCircle(state: CircleState) {
        primaryOverlayController<CircleState>(OverlayKind.Circle)?.update(state)
    }

    override fun hasCircle(state: CircleState): Boolean = hasOverlay(OverlayKind.Circle, state.id)

    @Deprecated("Use CircleState.onClick instead.")
    override fun setOnCircleClickListener(listener: OnCircleEventHandler?) {
        overlayControllersOf(OverlayKind.Circle).forEach { it.setClickListenerAny(listener) }
    }

    // GroundImage
    override suspend fun compositionGroundImages(data: List<GroundImageState>) {
        primaryOverlayController<GroundImageState>(OverlayKind.GroundImage)?.add(data)
    }

    override suspend fun updateGroundImage(state: GroundImageState) {
        primaryOverlayController<GroundImageState>(OverlayKind.GroundImage)?.update(state)
    }

    override fun hasGroundImage(state: GroundImageState): Boolean = hasOverlay(OverlayKind.GroundImage, state.id)

    @Deprecated("Use GroundImageState.onClick instead.")
    override fun setOnGroundImageClickListener(listener: OnGroundImageEventHandler?) {
        overlayControllersOf(OverlayKind.GroundImage).forEach { it.setClickListenerAny(listener) }
    }

    // RasterLayer
    override suspend fun compositionRasterLayers(data: List<RasterLayerState>) {
        primaryOverlayController<RasterLayerState>(OverlayKind.RasterLayer)?.add(data)
    }

    override suspend fun updateRasterLayer(state: RasterLayerState) {
        primaryOverlayController<RasterLayerState>(OverlayKind.RasterLayer)?.update(state)
    }

    override fun hasRasterLayer(state: RasterLayerState): Boolean = hasOverlay(OverlayKind.RasterLayer, state.id)

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
