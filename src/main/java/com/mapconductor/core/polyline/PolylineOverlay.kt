package com.mapconductor.core.polyline

import com.mapconductor.core.OverlayAddedTracker
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class PolylineOverlay
    @JvmOverloads
    constructor(
        override val flow: StateFlow<MutableMap<String, PolylineState>>,
        /**
         * This map's polyline tiling, when the app sets one (null keeps the
         * provider's [PolylineTilingOptions.Default]). Applied to the map's
         * [PolylineTiler] before every hand-over, so a change takes effect the
         * next time the polylines are handed over.
         */
        private val tilingOptions: StateFlow<PolylineTilingOptions?>? = null,
    ) : MapOverlayInterface<PolylineState> {
        /** Calls [PolylineState.onAdded] once the map has the state. See [OverlayAddedTracker]. */
        private val added = OverlayAddedTracker<PolylineState> { it.onAdded }

        override suspend fun render(
            data: MutableMap<String, PolylineState>,
            controller: MapViewControllerInterface,
        ) {
            val capable = controller as? PolylineCapableInterface ?: return
            tilingOptions?.value?.let { options ->
                (controller as? BaseMapViewController)?.polylineTilers()?.forEach { it.options = options }
            }
            val states = data.values.toList()
            capable.compositionPolylines(states)
            added.synced(controller, states, capable::hasPolyline)
        }
    }
