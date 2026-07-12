package com.mapconductor.core.controller

import com.mapconductor.core.features.GeoPointInterface

interface OverlayControllerInterface<StateType, EntityType> {
    val zIndex: Int

    suspend fun add(data: List<StateType>)

    suspend fun update(state: StateType)

    suspend fun clear()
    fun find(position: GeoPointInterface): EntityType?

    /**
     * Cleanup resources when the controller is no longer needed.
     * IMPORTANT: Call this when switching map providers or disposing the map.
     */
    fun destroy()
}
