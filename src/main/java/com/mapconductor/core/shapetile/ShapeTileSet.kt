package com.mapconductor.core.shapetile

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.tileserver.LocalTileServer
import java.util.BitSet
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private val canReuse: ((S, T) -> Boolean)? = null,
    private val createStorage: (suspend (List<S>) -> ShapeTileStorage<T>?)? = null,
) {
    val routeId: String = "$routePrefix-${UUID.randomUUID().toString().take(8)}"

    /** Called with the new template after an [update] or [remove]. */
    var onTemplateChanged: (suspend (String) -> Unit)? = null

    val tileSize: Int get() = renderer.tileSize

    private val lock = Any()
    private var indexById: Map<String, Int> = emptyMap()
    private var sourceStates: MutableList<S?> = mutableListOf()
    private var sourceZ = IntArray(0)
    private var active: BitSet? = null

    fun ids(): Set<String> =
        synchronized(lock) {
            val mask = active ?: return@synchronized indexById.keys
            val ids = indexById
            object : AbstractSet<String>() {
                override val size = mask.cardinality()

                override fun contains(element: String): Boolean = ids[element]?.let { mask[it] } == true

                override fun iterator(): Iterator<String> =
                    ids.entries
                        .asSequence()
                        .filter { mask[it.value] }
                        .map { it.key }
                        .iterator()
            }
        }

    fun source(id: String): S? =
        synchronized(lock) {
            indexById[id]?.takeIf { active?.get(it) != false }?.let { sourceStates[it] }
        }

    fun visitSources(action: (S) -> Unit) =
        synchronized(lock) {
            for ((index, state) in sourceStates.withIndex()) {
                if (state != null &&
                    active?.get(index) != false
                ) {
                    action(state)
                }
            }
        }

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
        val ordered = withContext(Dispatchers.Default) { states.sortedBy(zIndexOf) }
        // A filtered collection often refers to the same geometry. Keep the
        // original slots, including temporarily hidden shapes, for re-enabling.
        val reused =
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                synchronized(lock) {
                    val matches = canReuse
                    if (!registered || matches == null) {
                        false
                    } else {
                        val mask = BitSet(sourceStates.size)
                        var previous = -1
                        var checked = 0
                        val reusable =
                            ordered.all { state ->
                                if (++checked % 4096 == 0) context.ensureActive()
                                val index = indexById[idOf(state)]
                                val item = index?.let(renderer::itemAt)
                                val ok =
                                    index != null &&
                                        index > previous &&
                                        item != null &&
                                        sourceZ[index] == zIndexOf(state) &&
                                        matches(state, item)
                                if (ok) {
                                    mask.set(index!!)
                                    previous = index
                                }
                                ok
                            }
                        if (reusable) {
                            // Cache geometry, not removed application states/handlers.
                            for (index in sourceStates.indices) if (!mask[index]) sourceStates[index] = null
                            for (state in ordered) sourceStates[indexById.getValue(idOf(state))] = state
                            active = mask
                            renderer.setActiveItems(mask)
                            version++
                        }
                        reusable
                    }
                }
            }
        if (reused) return template()
        var prepared: ShapeTileStorage<T>? = null
        try {
            val (shapes, ids, sources) =
                withContext(Dispatchers.Default) {
                    // Draw order is list order: zIndex, then the order given (sortedBy is stable).
                    val ids = HashMap<String, Int>(ordered.size * 2)
                    ordered.forEachIndexed { index, state -> ids[idOf(state)] = index }
                    val sources: MutableList<S?> = ordered.toMutableList()
                    prepared = createStorage?.invoke(ordered)
                    Triple(if (prepared == null) ordered.map(toTile) else emptyList(), ids, sources)
                }
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                synchronized(lock) {
                    val storage = prepared
                    prepared = null
                    if (storage !=
                        null
                    ) {
                        renderer.setStorage(storage) { context.ensureActive() }
                    } else {
                        renderer.setItems(shapes)
                    }
                    if (!registered) server.register(routeId, renderer)
                    indexById = ids
                    sourceStates = sources
                    sourceZ = IntArray(ordered.size) { zIndexOf(ordered[it]) }
                    active = null
                    registered = true
                    version++
                }
            }
            return template()
        } finally {
            prepared?.release()
        }
    }

    fun has(id: String): Boolean =
        synchronized(lock) {
            indexById[id]?.let { active?.get(it) != false } == true
        }

    /**
     * One shape changed in place. A change of zIndex reorders the list and
     * needs [setShapes] instead; everything else is applied here.
     */
    fun update(state: S) {
        synchronized(lock) {
            val id = idOf(state)
            if (!indexById.containsKey(id)) return
            sourceStates[indexById.getValue(id)] = state
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
            sourceStates[index] = null
            indexById = indexById - id
            active?.let { previous ->
                val next = previous.clone() as BitSet
                next.clear(index)
                active = next
                renderer.setActiveItems(next)
            }
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
            sourceStates = mutableListOf()
            sourceZ = IntArray(0)
            active = null
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
