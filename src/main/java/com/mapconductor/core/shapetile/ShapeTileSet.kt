package com.mapconductor.core.shapetile

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.tileserver.LocalTileServer
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shapes of one map that are drawn as raster tiles, and their route on the
 * local tile server: the part `PolygonTileSet` and `PolylineTileSet` share.
 * Each of those adds what a state is as a tile ([toTile]) and which states can
 * be tiled.
 *
 * [setShapes] registers the list and returns the URL template of its tiles;
 * [update] and [remove] change one shape in place, coalesced over
 * [UPDATE_COALESCE], and each batch ends in a new template -- the version in
 * it is what makes the map ask again -- reported to [onTemplateChanged]. Tiles
 * the change did not touch are still cached by the renderer and come back
 * without being drawn again.
 *
 * The same as js-sdk-core's and ios-sdk-core's `ShapeTileSet`.
 */
@InternalMapConductorApi
internal class ShapeTileSet<S, T : Any>(
    routePrefix: String,
    private val server: LocalTileServer,
    private val scope: CoroutineScope,
    private val renderer: ShapeTileRenderer<T>,
    private val idOf: (S) -> String,
    private val zIndexOf: (S) -> Int,
    private val toTile: (S) -> T?,
) {
    val routeId: String = "$routePrefix-${UUID.randomUUID().toString().take(8)}"

    /** Called with the new template after an [update] or [remove]. */
    var onTemplateChanged: (suspend (String) -> Unit)? = null

    val tileSize: Int get() = renderer.tileSize

    private val lock = Any()
    private var indexById: Map<String, Int> = emptyMap()

    @Volatile
    private var version = 0
    private var registered = false
    private val pending = LinkedHashMap<String, S>()
    private val pendingRemovals = ArrayList<Int>()
    private var flushJob: Job? = null

    /**
     * Registers [states] (replacing whatever was registered) and returns the
     * URL template of their tiles.
     */
    suspend fun setShapes(states: List<S>): String {
        cancelPending()
        val (shapes, ids) =
            withContext(Dispatchers.Default) {
                // Draw order is list order: zIndex, then the order given (sortedBy is stable).
                val ordered = states.sortedBy(zIndexOf)
                val ids = HashMap<String, Int>(ordered.size * 2)
                ordered.forEachIndexed { index, state -> ids[idOf(state)] = index }
                ordered.map(toTile) to ids
            }
        synchronized(lock) {
            renderer.setItems(shapes)
            if (!registered) server.register(routeId, renderer)
            indexById = ids
            registered = true
            version++
        }
        return template()
    }

    fun has(id: String): Boolean = synchronized(lock) { indexById.containsKey(id) }

    /**
     * One shape changed in place. A change of zIndex reorders the list and
     * needs [setShapes] instead; everything else is applied here.
     */
    fun update(state: S) {
        synchronized(lock) {
            val id = idOf(state)
            if (!indexById.containsKey(id)) return
            pending[id] = state
            scheduleFlushLocked()
        }
    }

    /**
     * Takes one shape out of the tiles. Its slot in the renderer's list is
     * left empty, so the others keep their indices and only the tiles it was
     * in are drawn again.
     */
    fun remove(id: String) {
        synchronized(lock) {
            val index = indexById[id] ?: return
            indexById = indexById - id
            pending.remove(id)
            pendingRemovals.add(index)
            scheduleFlushLocked()
        }
    }

    /** Called with [lock] held. */
    private fun scheduleFlushLocked() {
        if (flushJob == null) {
            flushJob =
                scope.launch {
                    delay(UPDATE_COALESCE)
                    flush()
                }
        }
    }

    /** The URL template, with the version that changes on every change. */
    fun template(): String = server.urlTemplate(routeId, renderer.tileSize, version.toString())

    fun dispose() {
        cancelPending()
        synchronized(lock) {
            if (registered) server.unregister(routeId)
            registered = false
            indexById = emptyMap()
            renderer.clear()
        }
    }

    private suspend fun flush() {
        val template =
            synchronized(lock) {
                flushJob = null
                val updates: List<Pair<Int, T?>> =
                    pending.values.mapNotNull { state ->
                        indexById[idOf(state)]?.let { index -> index to toTile(state) }
                    } + pendingRemovals.map { index -> index to null }
                pending.clear()
                pendingRemovals.clear()
                if (updates.isEmpty() || !registered) return
                renderer.update(updates)
                version++
                template()
            }
        onTemplateChanged?.invoke(template)
    }

    private fun cancelPending() {
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = null
            pending.clear()
            pendingRemovals.clear()
        }
    }

    @InternalMapConductorApi
    companion object {
        /** In-place changes arriving within this window are drawn as one. */
        val UPDATE_COALESCE = 16.milliseconds
    }
}
