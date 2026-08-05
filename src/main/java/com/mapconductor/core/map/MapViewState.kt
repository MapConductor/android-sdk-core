package com.mapconductor.core.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import kotlinx.coroutines.flow.StateFlow

enum class InitState {
    NotStarted,
    Initializing,
    SdkInitialized,
    MapViewCreated,
    MapCreating,
    MapCreated,
    MapLoaded,
    Failed,
}

interface MapViewStateInterface<ActualMapDesignType> {
    val id: String
    val cameraPosition: MapCameraPosition
    var mapDesignType: ActualMapDesignType

    /** Which map gestures the user may perform. See [MapUISettings]. */
    var uiSettings: MapUISettings

    fun moveCameraTo(
        cameraPosition: MapCameraPosition,
        durationMillis: Long? = 0,
    )

    fun moveCameraTo(
        position: GeoPoint,
        durationMillis: Long? = 0,
    )

    fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int = 0,
    )

    fun getMapViewHolder(): MapViewHolderInterface<*, *>?

    fun getControllers(): Map<String, OverlayControllerInterface<*, *>>?
}

abstract class MapViewState<ActualMapDesignType> : MapViewStateInterface<ActualMapDesignType> {
    private val tag = this.javaClass.name

    // Backed by Compose state so flipping a gesture flag recomposes the map view.
    override var uiSettings: MapUISettings by mutableStateOf(MapUISettings.Default)
}

interface MapOverlayInterface<DataType> {
    val flow: StateFlow<MutableMap<String, DataType>>

    suspend fun render(
        data: MutableMap<String, DataType>,
        controller: MapViewControllerInterface,
    )
}

class MapOverlayRegistry {
    private val overlays = mutableListOf<MapOverlayInterface<*>>()

    fun register(overlay: MapOverlayInterface<*>) {
        if (overlays.toSet().contains(overlay)) return
        overlays.add(overlay)
    }

    fun getAll(): List<MapOverlayInterface<*>> = overlays.toList()
}
