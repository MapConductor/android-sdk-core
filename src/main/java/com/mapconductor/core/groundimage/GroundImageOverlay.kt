package com.mapconductor.core.groundimage

import com.mapconductor.core.OverlayAddedTracker
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class GroundImageOverlay(
    override val flow: StateFlow<MutableMap<String, GroundImageState>>,
) : MapOverlayInterface<GroundImageState> {
    /** Calls [GroundImageState.onAdded] once the map has the state. See [OverlayAddedTracker]. */
    private val added = OverlayAddedTracker<GroundImageState> { it.onAdded }

    override suspend fun render(
        data: MutableMap<String, GroundImageState>,
        controller: MapViewControllerInterface,
    ) {
        val capable = controller as? GroundImageCapableInterface ?: return
        val states = data.values.toList()
        capable.compositionGroundImages(states)
        added.synced(controller, states, capable::hasGroundImage)
    }
}
