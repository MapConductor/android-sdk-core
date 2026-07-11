package com.mapconductor.core.raster

import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class RasterLayerOverlay(
    override val flow: StateFlow<MutableMap<String, RasterLayerState>>,
) : MapOverlayInterface<RasterLayerState> {
    override suspend fun render(
        data: MutableMap<String, RasterLayerState>,
        controller: MapViewControllerInterface,
    ) {
        (controller as? RasterLayerCapableInterface)?.compositionRasterLayers(data.values.toList())
    }
}
