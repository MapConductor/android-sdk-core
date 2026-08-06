package com.mapconductor.core.circle

import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class CircleOverlay(
    override val flow: StateFlow<MutableMap<String, CircleState>>,
) : MapOverlayInterface<CircleState> {
    override suspend fun render(
        data: MutableMap<String, CircleState>,
        controller: MapViewControllerInterface,
    ) {
        (controller as? CircleCapableInterface)?.compositionCircles(data.values.toList())
    }
}
