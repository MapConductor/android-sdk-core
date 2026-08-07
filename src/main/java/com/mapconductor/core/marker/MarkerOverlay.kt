package com.mapconductor.core.marker

import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class MarkerOverlay(
    override val flow: StateFlow<MutableMap<String, MarkerState>>,
) : MapOverlayInterface<MarkerState> {
    override suspend fun render(
        data: MutableMap<String, MarkerState>,
        controller: MapViewControllerInterface,
    ) {
        (controller as? MarkerCapableInterface)?.let { markerController ->
            markerController.compositionMarkers(data.values.toList())
        }
    }
}
