package com.mapconductor.core.polygon

import androidx.compose.runtime.compositionLocalOf
import com.mapconductor.core.ChildCollector
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class PolygonOverlay(
    override val flow: StateFlow<MutableMap<String, PolygonState>>,
) : MapOverlayInterface<PolygonState> {
    override suspend fun render(
        data: MutableMap<String, PolygonState>,
        controller: MapViewControllerInterface,
    ) {
        (controller as? PolygonCapableInterface)?.compositionPolygons(data.values.toList())
    }
}
