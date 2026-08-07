package com.mapconductor.core

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewStateInterface

typealias OnMapLoadedHandler = (MapViewStateInterface<*>) -> Unit
typealias OnMapInitializedHandler = () -> Unit
typealias OnMapEventHandler = (GeoPoint) -> Unit
typealias OnCameraMoveHandler = (MapCameraPosition) -> Unit
