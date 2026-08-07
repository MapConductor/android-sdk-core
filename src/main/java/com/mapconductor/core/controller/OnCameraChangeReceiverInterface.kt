package com.mapconductor.core.controller

import com.mapconductor.core.map.MapCameraPosition

interface OnCameraChangeReceiverInterface {
    suspend fun onCameraChanged(mapCameraPosition: MapCameraPosition)
}
