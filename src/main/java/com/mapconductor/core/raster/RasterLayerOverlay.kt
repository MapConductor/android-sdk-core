package com.mapconductor.core.raster

import com.mapconductor.core.OverlayAddedTracker
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class RasterLayerOverlay(
    override val flow: StateFlow<MutableMap<String, RasterLayerState>>,
) : MapOverlayInterface<RasterLayerState> {
    /** Calls [RasterLayerState.onAdded] once the map has the state. See [OverlayAddedTracker]. */
    private val added = OverlayAddedTracker<RasterLayerState> { it.onAdded }

    override suspend fun render(
        data: MutableMap<String, RasterLayerState>,
        controller: MapViewControllerInterface,
    ) {
        val capable = controller as? RasterLayerCapableInterface ?: return
        val states = data.values.toList()
        capable.compositionRasterLayers(states)
        added.synced(controller, states, capable::hasRasterLayer)
    }
}
