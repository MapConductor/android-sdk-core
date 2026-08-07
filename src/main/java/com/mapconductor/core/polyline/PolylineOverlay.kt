package com.mapconductor.core.polyline

import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class PolylineOverlay(
    override val flow: StateFlow<MutableMap<String, PolylineState>>,
) : MapOverlayInterface<PolylineState> {
    override suspend fun render(
        data: MutableMap<String, PolylineState>,
        controller: MapViewControllerInterface,
    ) {
        (controller as? PolylineCapableInterface)?.compositionPolylines(data.values.toList())
    }
}
