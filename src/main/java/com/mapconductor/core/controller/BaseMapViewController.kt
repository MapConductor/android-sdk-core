package com.mapconductor.core.controller

import com.mapconductor.core.OnCameraMoveHandler
import com.mapconductor.core.OnMapEventHandler
import com.mapconductor.core.OnMapInitializedHandler
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface
import java.util.concurrent.CopyOnWriteArrayList
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
}
