package com.mapconductor.core

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.yield

/**
 * Calls each state's `onAdded` once the map has it.
 *
 * One per overlay type per map, kept by the overlay that hands that type's
 * states to the map ([com.mapconductor.core.polygon.PolygonOverlay] and the
 * others). After every hand-over -- `compositionXxx` has returned, so the
 * provider has made its objects or handed the tile template to the map -- it
 * asks the map which of the states it does not yet count as "on the map" now
 * are (`hasXxx`) and calls `onAdded` on those.
 *
 * It lives here rather than in each overlay controller because that is the one
 * place every provider's overlays pass through: several providers add markers
 * or polygons through their own controllers (MapLibre / Mapbox / Mappls
 * polygon conductors, the per-provider marker controllers with tiling, Longdo
 * and MapTiler's Compose-drawn markers), and `hasXxx` is already how each one
 * says what it has.
 *
 * - An in-place change never reaches here (it goes to `updateXxx`), so it
 *   calls nothing. Neither does a move between native drawing and tiles: the
 *   id stays on the map.
 * - An id that leaves the list is forgotten, so adding it again calls again.
 * - Clearing or destroying the map calls nothing. A different controller (the
 *   map was recreated) starts from nothing.
 *
 * Not thread-safe: the overlays call it from the composition's coroutine,
 * i.e. the main thread, and that is where the callbacks run. Asking the map
 * about 1e5 states takes a couple of hundred ms there, so the asking yields
 * every [CHECKS_PER_YIELD] states to let frames through. If the composition
 * moves on meanwhile (the coroutine is cancelled), nothing is recorded or
 * called; the next hand-over asks again.
 */
@InternalMapConductorApi
internal class OverlayAddedTracker<T : ComponentState>(
    private val onAddedOf: (T) -> ((T) -> Unit)?,
) {
    private var controller: Any? = null
    private var onMap: Set<String> = emptySet()

    /**
     * [data] has just been handed to [controller]. Calls `onAdded` on the
     * states [isOnMap] now reports that were not on the map before.
     */
    suspend fun synced(
        controller: Any,
        data: Collection<T>,
        isOnMap: (T) -> Boolean,
        sourceIds: Set<String>? = null,
    ) {
        if (controller !== this.controller) {
            this.controller = controller
            onMap = emptySet()
        }
        val previous = onMap
        // A completed bulk composition can share its immutable generation's
        // source-map keys. Allocate a new ID set only for partial admission.
        val next = if (sourceIds == null) HashSet<String>(data.size * 2) else null
        var missing: MutableSet<String>? = null
        var added: MutableList<T>? = null
        var checked = 0
        val listening = OverlayAddedListeners.active
        for (state in data) {
            if (++checked % CHECKS_PER_YIELD == 0) yield()
            val id = state.id
            if (id in previous) {
                next?.add(id)
            } else if (isOnMap(state)) {
                next?.add(id)
                if (listening || onAddedOf(state) != null) (added ?: ArrayList<T>().also { added = it }).add(state)
            } else if (sourceIds != null) {
                (missing ?: HashSet<String>().also { missing = it }).add(id)
            }
        }
        // Recorded and called without suspending, so a cancellation cannot
        // leave some of them called and the rest forgotten.
        onMap = next ?: if (missing == null) sourceIds!! else sourceIds!!.filterTo(HashSet()) { it !in missing!! }
        added?.let(::notify)
    }

    private fun notify(states: List<T>) {
        OverlayAddedListeners.notify(states)
        for (state in states) {
            val callback = onAddedOf(state) ?: continue
            try {
                callback(state)
            } catch (e: Exception) {
                // One app callback that throws must not keep the others from running.
                runCatching { Log.e("MapConductor", "onAdded of ${state.id} threw", e) }
            }
        }
    }

    @InternalMapConductorApi
    private companion object {
        const val CHECKS_PER_YIELD = 4096
    }
}

/**
 * Hears every batch of states [OverlayAddedTracker] finds on the map, before
 * their `onAdded` calls, on the main thread. For the framework layer (the
 * Compose collections count their own states with it), not for apps.
 */
@InternalMapConductorApi
object OverlayAddedListeners {
    private val listeners = CopyOnWriteArrayList<(List<ComponentState>) -> Unit>()

    internal val active: Boolean
        get() = listeners.isNotEmpty()

    /** Adds [listener]; call the returned function to remove it. */
    fun add(listener: (List<ComponentState>) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    internal fun notify(states: List<ComponentState>) {
        for (listener in listeners) {
            try {
                listener(states)
            } catch (e: Exception) {
                runCatching { Log.e("MapConductor", "an overlay-added listener threw", e) }
            }
        }
    }
}
