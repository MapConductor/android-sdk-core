package com.mapconductor.core.circle

import com.mapconductor.core.OverlayAddedTracker
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class CircleOverlay(
    override val flow: StateFlow<MutableMap<String, CircleState>>,
) : MapOverlayInterface<CircleState> {
    /** Calls [CircleState.onAdded] once the map has the state. See [OverlayAddedTracker]. */
    private val added = OverlayAddedTracker<CircleState> { it.onAdded }

    override suspend fun render(
        data: MutableMap<String, CircleState>,
        controller: MapViewControllerInterface,
    ) {
        val capable = controller as? CircleCapableInterface ?: return
        val states = data.values.toList()
        capable.compositionCircles(states)
        added.synced(controller, states, capable::hasCircle)
    }
}
