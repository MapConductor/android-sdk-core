package com.mapconductor.core.controller

import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface

interface MapViewControllerInterface {
    val holder: MapViewHolderInterface<*, *>
    suspend fun clearOverlays()

    fun moveCamera(position: MapCameraPosition)

    fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    )

    fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    )
    fun registerOverlayController(controller: OverlayControllerInterface<*, *, *>)

    fun getControllers(): List<OverlayControllerInterface<*, *, *>>

    fun destroy()
}
