package com.mapconductor.core.marker

import com.mapconductor.core.OverlayAddedTracker
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class MarkerOverlay(
    override val flow: StateFlow<MutableMap<String, MarkerState>>,
) : MapOverlayInterface<MarkerState> {
    /** Calls [MarkerState.onAdded] once the map has the state. See [OverlayAddedTracker]. */
    private val added = OverlayAddedTracker<MarkerState> { it.onAdded }

    override suspend fun render(
        data: MutableMap<String, MarkerState>,
        controller: MapViewControllerInterface,
    ) {
        val capable = controller as? MarkerCapableInterface ?: return
        val states = data.values.toList()
        capable.compositionMarkers(states)
        added.synced(controller, states, capable::hasMarker)
    }
}
