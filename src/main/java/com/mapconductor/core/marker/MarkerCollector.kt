package com.mapconductor.core.marker

import com.mapconductor.core.OverlayCollector
import com.mapconductor.core.OverlayCollectorInterface
import com.mapconductor.settings.Settings
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class MarkerCollector(
    updateDebounce: Duration = Settings.Default.composeEventDebounce,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
) : OverlayCollectorInterface<MarkerState> by OverlayCollector(
        fingerPrintOf = { it.fingerPrint() },
        updateDebounce = updateDebounce,
        scope = scope,
    )
