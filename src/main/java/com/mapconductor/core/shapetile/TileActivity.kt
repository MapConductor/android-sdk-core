package com.mapconductor.core.shapetile

import com.mapconductor.core.InternalMapConductorApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a tiler is busy: taking a list (`route`) or drawing tiles for the
 * map. The collections' onAddStart / onAddEnd read it, so that an app can show
 * that the map is still drawing after a filter change or a camera move.
 *
 * Counted, since tiles are drawn on several threads at once.
 */
@InternalMapConductorApi
class TileActivity {
    private var count = 0
    private val busy = MutableStateFlow(false)

    val active: StateFlow<Boolean> = busy.asStateFlow()

    fun begin() =
        synchronized(this) {
            if (++count == 1) busy.value = true
        }

    fun end() =
        synchronized(this) {
            if (--count == 0) busy.value = false
        }

    inline fun <R> during(block: () -> R): R {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }
}
