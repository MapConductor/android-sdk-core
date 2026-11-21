package com.mapconductor.core.shapetile

import androidx.collection.MutableObjectIntMap
import androidx.collection.ObjectIntMap
import androidx.collection.emptyObjectIntMap
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
    // An object that is the same (===) while the state's values are unchanged and
    // is replaced by any write; CircleState's snapshot record. With it a state
    // registered before and untouched since is known to match its slot without
    // reading the slot's geometry, which a file-backed set reads from disk.
    private val snapshotOf: ((S) -> Any)? = null,
) {
    val routeId: String = "$routePrefix-${UUID.randomUUID().toString().take(8)}"

    /** Called with the new template after an [update] or [remove]. */
    var onTemplateChanged: (suspend (String) -> Unit)? = null

    val tileSize: Int get() = renderer.tileSize

    /** Busy while tiles of this set are drawn, and while [ShapeTiler] hands it a list. */
    val activity: TileActivity get() = renderer.activity

    private val lock = Any()
    // Never changed once assigned, so the sets [ids] hands out stay as they were.
    // An ObjectIntMap rather than a HashMap<String, Int>: no node or boxed Int
    // per shape, which was 50 MB with 720k circles.
    private var indexById: ObjectIntMap<String> = emptyObjectIntMap()
    private var sourceStates: MutableList<S?> = mutableListOf()
    private var sourceZ = IntArray(0)

    // [snapshotOf] of the state each slot was drawn from, or null when unknown.
    // Kept for inactive slots too, so a shape filtered out and back in is known.
    private var sourceSnapshots: Array<Any?> = arrayOfNulls(0)
    private var active: BitSet? = null

    fun ids(): Set<String> =
        synchronized(lock) {
            val mask = active
            val ids = indexById
            object : AbstractSet<String>() {
                override val size = mask?.cardinality() ?: ids.size

                override fun contains(element: String): Boolean {
                    val index = ids.getOrDefault(element, -1)
                    return index >= 0 && mask?.get(index) != false
                }

                override fun iterator(): Iterator<String> {
                    val keys = ArrayList<String>(size)
                    ids.forEach { id, index -> if (mask?.get(index) != false) keys.add(id) }
                    return keys.iterator()
                }
            }
        }

    /** The slot of [id], or -1. Called with [lock] held. */
    private fun indexOf(id: String): Int = indexById.getOrDefault(id, -1)

    fun source(id: String): S? =
        synchronized(lock) {
            indexOf(id).takeIf { it >= 0 && active?.get(it) != false }?.let { sourceStates[it] }
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

    /**
     * The registered states whose shape's bounding box meets the given one (Web
     * Mercator, [0, 1]), as of now: changes still waiting for [flush] are
     * included, since their shape in the renderer may be the old one.
     */
    fun sources(
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
    ): List<S> {
        val slots = renderer.candidates(minX, minY, maxX, maxY)
        return synchronized(lock) {
            val seen = HashSet<String>()
            val states = ArrayList<S>(slots.size)
            for (slot in slots) {
                if (slot >= sourceStates.size || active?.get(slot) == false) continue
                val state = sourceStates[slot] ?: continue
                if (seen.add(idOf(state))) states.add(state)
            }
            for ((id, state) in pending) {
                if (indexById.contains(id) && seen.add(id)) states.add(state)
            }
            states
        }
    }

    /** The widest stroke drawn, in pixels of a tile. */
    val maxStroke: Float get() = renderer.maxStroke

    @Volatile
    private var version = 0
    private var registered = false
    private val pending = LinkedHashMap<String, S>()
    private val pendingRemovals = ArrayList<Int>()
    private var flushJob: Job? = null

    /**
     * [states] in draw order (zIndex, then the order given) with their zIndex.
     * Each zIndex is read once: reading a state is not free, and a sort by a
     * selector reads it at every comparison.
     */
    private fun orderByZ(states: List<S>): Pair<List<S>, IntArray> {
        val z = IntArray(states.size) { zIndexOf(states[it]) }
        // zIndex in the high half, position in the low: one primitive sort, stable by position.
        val keys = LongArray(states.size) { (z[it].toLong() shl 32) or it.toLong() }
        keys.sort()
        val ordered = ArrayList<S>(states.size)
        val orderedZ = IntArray(states.size)
        for ((k, key) in keys.withIndex()) {
            val i = key.toInt()
            ordered.add(states[i])
            orderedZ[k] = z[i]
        }
        return ordered to orderedZ
    }

    /**
     * The states of [states] that [canTile] accepts, without asking it about a
     * state registered here before and unchanged since: it was tileable then,
     * and [canTile] reads only its values.
     */
    fun filterTileable(
        states: List<S>,
        canTile: (S) -> Boolean,
    ): List<S> {
        val snapshot = snapshotOf ?: return states.filter(canTile)
        val (ids, snapshots) = synchronized(lock) { indexById to sourceSnapshots }
        return states.filter { state ->
            val index = ids.getOrDefault(idOf(state), -1)
            (index >= 0 && snapshots[index] != null && snapshots[index] === snapshot(state)) || canTile(state)
        }
    }

    /**
     * Registers [states] (replacing whatever was registered) and returns the
     * URL template of their tiles.
     */
    suspend fun setShapes(states: List<S>): String {
        cancelPending()
        val (ordered, orderedZ) = withContext(Dispatchers.Default) { orderByZ(states) }
        // Taken before any geometry is made from the states: a write in between
        // then shows as a different snapshot, never as a stale match.
        val snapshots: Array<Any?>? =
            snapshotOf?.let { snapshot -> withContext(Dispatchers.Default) { Array(ordered.size) { snapshot(ordered[it]) } } }
        // A filtered collection often refers to the same geometry. Keep the
        // original slots, including temporarily hidden shapes, for re-enabling.
        val reused =
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                synchronized(lock) { reuseLocked(ordered, orderedZ, snapshots) { context.ensureActive() } }
            }
        if (reused) return template()
        var prepared: ShapeTileStorage<T>? = null
        try {
            val (shapes, ids, sources) =
                withContext(Dispatchers.Default) {
                    // Draw order is list order: zIndex, then the order given (sortedBy is stable).
                    val ids = MutableObjectIntMap<String>(ordered.size)
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
                    sourceZ = orderedZ
                    sourceSnapshots = snapshots ?: arrayOfNulls(ordered.size)
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

    /**
     * Shows [ordered] with the slots already registered, if each of them is in
     * one, in the same order, with the same zIndex and the same geometry.
     *
     * A state whose snapshot is the one its slot was drawn from matches without
     * reading the slot. The rest are compared with their slot's geometry in one
     * pass in storage order: reading a file-backed set one slot at a time in
     * draw order missed its block cache on nearly every read.
     */
    private fun reuseLocked(
        ordered: List<S>,
        orderedZ: IntArray,
        snapshots: Array<Any?>?,
        checkCancelled: () -> Unit,
    ): Boolean {
        val matches = canReuse
        if (!registered || matches == null) return false
        val mask = BitSet(sourceStates.size)
        val slots = IntArray(ordered.size)
        var unknown = 0
        val toCompare = IntArray(ordered.size)
        var previous = -1
        for ((k, state) in ordered.withIndex()) {
            if (k % 4096 == 0) checkCancelled()
            val index = indexOf(idOf(state))
            if (index <= previous || sourceZ[index] != orderedZ[k]) return false
            val known = snapshots != null && snapshots[k] != null && snapshots[k] === sourceSnapshots[index]
            if (!known) toCompare[unknown++] = k
            slots[k] = index
            mask.set(index)
            previous = index
        }
        if (unknown > 0) {
            val compared = IntArray(unknown) { slots[toCompare[it]] }
            var ok = true
            renderer.forEachItem(compared, checkCancelled) { j, item ->
                if (ok && (item == null || !matches(ordered[toCompare[j]], item))) ok = false
            }
            if (!ok) return false
        }
        // Cache geometry, not removed application states/handlers.
        for (index in sourceStates.indices) if (!mask[index]) sourceStates[index] = null
        for ((k, state) in ordered.withIndex()) sourceStates[slots[k]] = state
        if (snapshots != null) for (j in 0 until unknown) sourceSnapshots[slots[toCompare[j]]] = snapshots[toCompare[j]]
        active = mask
        renderer.setActiveItems(mask)
        version++
        return true
    }

    fun has(id: String): Boolean =
        synchronized(lock) {
            indexOf(id).let { it >= 0 && active?.get(it) != false }
        }

    /**
     * One shape changed in place. A change of zIndex reorders the list and
     * needs [setShapes] instead; everything else is applied here.
     */
    fun update(state: S) {
        synchronized(lock) {
            val id = idOf(state)
            val index = indexOf(id)
            if (index < 0) return
            sourceStates[index] = state
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
            val index = indexOf(id)
            if (index < 0) return
            sourceStates[index] = null
            val previous = indexById
            indexById =
                MutableObjectIntMap<String>(previous.size).also { next ->
                    previous.forEach { key, value -> if (key != id) next[key] = value }
                }
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
            indexById = emptyObjectIntMap()
            sourceStates = mutableListOf()
            sourceZ = IntArray(0)
            sourceSnapshots = arrayOfNulls(0)
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
                        indexOf(idOf(state)).takeIf { it >= 0 }?.let { index -> index to toTile(state) }
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
