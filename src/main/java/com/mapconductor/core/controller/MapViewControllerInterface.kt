package com.mapconductor.core.controller

import com.mapconductor.core.OnCameraMoveHandler
import com.mapconductor.core.OnMapEventHandler
import com.mapconductor.core.OnMapInitializedHandler
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface
import kotlinx.coroutines.CoroutineScope

interface MapViewControllerInterface {
    val holder: MapViewHolderInterface<*, *>
    val coroutine: CoroutineScope

    suspend fun clearOverlays()

    fun setCameraMoveStartListener(listener: OnCameraMoveHandler?)

    fun setCameraMoveListener(listener: OnCameraMoveHandler?)

    fun setCameraMoveEndListener(listener: OnCameraMoveHandler?)

    fun setMapClickListener(listener: OnMapEventHandler?)

    fun setMapLongClickListener(listener: OnMapEventHandler?)

    fun setMapInitializedListener(listener: OnMapInitializedHandler?)

    fun moveCamera(position: MapCameraPosition)

    fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    )

    fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    )

    fun registerOverlayController(controller: OverlayControllerInterface<*, *, *>) {}
}
