package com.mapconductor.core.polyline

import com.mapconductor.core.OverlayAddedTracker
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class PolylineOverlay(
    override val flow: StateFlow<MutableMap<String, PolylineState>>,
) : MapOverlayInterface<PolylineState> {
    /** Calls [PolylineState.onAdded] once the map has the state. See [OverlayAddedTracker]. */
    private val added = OverlayAddedTracker<PolylineState> { it.onAdded }

    override suspend fun render(
        data: MutableMap<String, PolylineState>,
        controller: MapViewControllerInterface,
    ) {
        val capable = controller as? PolylineCapableInterface ?: return
        val states = data.values.toList()
        capable.compositionPolylines(states)
        added.synced(controller, states, capable::hasPolyline)
    }
}
