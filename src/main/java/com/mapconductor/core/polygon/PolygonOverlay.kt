package com.mapconductor.core.polygon

import com.mapconductor.core.OverlayAddedTracker
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class PolygonOverlay(
    override val flow: StateFlow<MutableMap<String, PolygonState>>,
) : MapOverlayInterface<PolygonState> {
    /** Calls [PolygonState.onAdded] once the map has the state. See [OverlayAddedTracker]. */
    private val added = OverlayAddedTracker<PolygonState> { it.onAdded }

    override suspend fun render(
        data: MutableMap<String, PolygonState>,
        controller: MapViewControllerInterface,
    ) {
        val capable = controller as? PolygonCapableInterface ?: return
        val states = data.values.toList()
        capable.compositionPolygons(states)
        added.synced(controller, states, capable::hasPolygon)
    }
}
