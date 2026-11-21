package com.mapconductor.core.controller

import com.mapconductor.core.InternalMapConductorApi
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether each tiler of [kind] on this map is busy -- taking a list or drawing
 * tiles -- for the collections' onAddStart / onAddEnd. Empty for a kind without
 * tiles.
 */
@InternalMapConductorApi
fun BaseMapViewController.overlayTileActivity(kind: OverlayKind): List<StateFlow<Boolean>> =
    when (kind) {
        OverlayKind.Circle -> circleTilers().map { it.activity }
        OverlayKind.Polyline -> polylineTilers().map { it.activity }
        OverlayKind.Polygon -> polygonTilers().map { it.activity }
        else -> emptyList()
    }
