package com.mapconductor.core.controller

import com.mapconductor.core.map.OnMapInitializedHandler
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.OnCameraMoveHandler
import com.mapconductor.core.map.OnMapEventHandler

abstract class BaseMapViewController : MapViewControllerInterface {
    private var overlayControllers = mutableListOf<OverlayControllerInterface<*, *, *>>()
    protected var cameraMoveStartCallback: OnCameraMoveHandler? = null
    protected var cameraMoveCallback: OnCameraMoveHandler? = null
    protected var cameraMoveEndCallback: OnCameraMoveHandler? = null
    protected var mapClickCallback: OnMapEventHandler? = null
    protected var mapLongClickCallback: OnMapEventHandler? = null

    protected var mapInitializedCallback: OnMapInitializedHandler? = null

    private var userMapClickCallback: OnMapEventHandler? = null
    private val extraMapClickHandlers = CopyOnWriteArrayList<OnMapEventHandler>()

    /**
     * Combined click callback: calls the user-supplied listener and all registered handlers.
     * Subclasses read this property and invoke it from their native map click callback.
     */
    protected val mapClickCallback: OnMapEventHandler?
        get() {
            val user = userMapClickCallback
            val extra = extraMapClickHandlers
            if (user == null && extra.isEmpty()) return null
            return { geoPoint ->
                user?.invoke(geoPoint)
                extra.forEach { it.invoke(geoPoint) }
            }
        }

    override fun setCameraMoveStartListener(listener: OnCameraMoveHandler?) {
        this.cameraMoveStartCallback = listener
    }

    override fun setCameraMoveListener(listener: OnCameraMoveHandler?) {
        this.cameraMoveCallback = listener
    }

    override fun setCameraMoveEndListener(listener: OnCameraMoveHandler?) {
        this.cameraMoveEndCallback = listener
    }

    override fun setMapClickListener(listener: OnMapEventHandler?) {
        this.mapClickCallback = listener
    }

    override fun setMapLongClickListener(listener: OnMapEventHandler?) {
        this.mapLongClickCallback = listener
    }

    protected fun registerController(controller: OverlayControllerInterface<*, *, *>) {
        if (overlayControllers.contains(controller)) return
        overlayControllers.add(controller)
    }

    override fun registerOverlayController(controller: OverlayControllerInterface<*, *, *>) {
        registerController(controller)
    }

    protected suspend fun notifyMapCameraPosition(mapCameraPosition: MapCameraPosition) {
        overlayControllers.forEach {
            it.onCameraChanged(mapCameraPosition)
        }
        cameraMoveCallback?.let { callBack ->
            callBack(mapCameraPosition)
        }
    }
}
