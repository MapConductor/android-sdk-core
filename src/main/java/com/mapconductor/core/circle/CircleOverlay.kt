package com.mapconductor.core.circle

import com.mapconductor.core.OverlayAddedTracker
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.map.MapOverlayInterface
import kotlinx.coroutines.flow.StateFlow

class CircleOverlay
    @JvmOverloads
    constructor(
        override val flow: StateFlow<MutableMap<String, CircleState>>,
        /**
         * This map's circle tiling, when the app sets one (null keeps the
         * provider's [CircleTilingOptions.Default]). Applied to the map's
         * [CircleTiler] before every hand-over, so a change takes effect the
         * next time the circles are handed over.
         */
        private val tilingOptions: StateFlow<CircleTilingOptions?>? = null,
    ) : MapOverlayInterface<CircleState> {
        /** Calls [CircleState.onAdded] once the map has the state. See [OverlayAddedTracker]. */
        private val added = OverlayAddedTracker<CircleState> { it.onAdded }

        override suspend fun render(
            data: MutableMap<String, CircleState>,
            controller: MapViewControllerInterface,
        ) {
            val capable = controller as? CircleCapableInterface ?: return
            tilingOptions?.value?.let { options ->
                (controller as? BaseMapViewController)?.circleTilers()?.forEach { it.options = options }
            }
            val states = data.values.toList()
            capable.compositionCircles(states)
            added.synced(controller, states, capable::hasCircle, sourceIds = data.keys)
        }
    }
