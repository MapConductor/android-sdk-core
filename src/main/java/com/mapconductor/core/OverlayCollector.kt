package com.mapconductor.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

interface ComponentState {
    val id: String

    /**
     * Where this state reports writes to its own fields.
     *
     * Every implementation owns one and routes its mutable fields through
     * [StateMutationSignal.notifying]. Without that the collector never hears
     * about an in-place change and the renderer keeps drawing the old value —
     * silently, which is why this is required rather than defaulted.
     */
    val mutations: StateMutationSignal
}

/**
 * The read/write contract of an [OverlayCollector], with the fingerprint type
 * erased.
 *
 * Consumers (`CompositionLocal`s, `MarkerCollector`'s delegation, extension
 * modules such as marker clustering) only ever need `T`.
 *
 * It carried a second type parameter until the collector stopped diffing
 * fingerprints to find in-place changes; that is what this interface existed to
 * keep out of every signature, and both are now gone.
 */
interface OverlayCollectorInterface<T : ComponentState> {
    val flow: MutableStateFlow<MutableMap<String, T>>

    suspend fun add(state: T)

    fun remove(id: String)

    fun setUpdateHandler(handler: (suspend (T) -> Unit)?)

    fun replaceAll(states: List<T>)
}

/**
 * Per-map, per-overlay-type source of truth for overlay states.
 *
 * Same role and name as `ios-sdk-core`'s `OverlayCollector.swift` and
 * `js-sdk-core`'s `overlay/OverlayCollector.ts`: one collector per overlay type
 * per map, holding an `id -> state` map that the renderer subscribes to.
 *
 * Two independent change channels, and the three platforms agree on the shape
 * of each:
 *
 * - **Membership** (add / remove) is **debounced**: a 5ms quiet window that each
 *   event extends, with a count valve so a large mount does not wait for the
 *   burst to end. Delivered through [flow].
 * - **In-place mutation** of an already-collected state is **sampled**: at most
 *   one delivery per 5ms window per state, latest value wins. Delivered through
 *   the update handler, never through [flow].
 */
class OverlayCollector<T : ComponentState>(
    private val updateDebounce: Duration,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
) : OverlayCollectorInterface<T> {
    private val scope = scope
    private val addSharedFlow = MutableSharedFlow<T>(1000)
    private val removeSharedFlow = MutableSharedFlow<String>(1000)

    @Volatile private var updateHandler: (suspend (T) -> Unit)? = null
    private var watcherJob: Job? = null

    /**
     * Where mutated states queue up for the watcher, or null when nobody is
     * listening — a state left registered would otherwise fill it forever.
     *
     * Replaced rather than drained when the handler changes: whatever the old
     * watcher had not delivered is about to be delivered to the wrong handler.
     */
    @Volatile private var mutated: Channel<T>? = null

    override val flow = MutableStateFlow<MutableMap<String, T>>(mutableMapOf())

    init {
        scope.launch {
            addSharedFlow.debounceBatch(5.milliseconds, 100).collect { states ->
                val newMap = flow.value.toMutableMap()
                states.forEach { state ->
                    newMap[state.id] = state
                }
                flow.value = newMap
            }
        }

        scope.launch {
            removeSharedFlow.debounceBatch(5.milliseconds, 300).collect { ids ->
                val newMap = flow.value.toMutableMap()
                ids.forEach { id ->
                    newMap.remove(id)
                }
                flow.value = newMap
            }
        }
    }

    override suspend fun add(state: T) {
        listen(state)
        addSharedFlow.emit(state)
    }

    override fun remove(id: String) {
        unlisten(id)
        removeSharedFlow.tryEmit(id)
    }

    /**
     * Who is currently reporting mutations, kept here rather than read back out
     * of [flow].
     *
     * Membership reaches [flow] through a debounce, so a state added and
     * removed inside the same window is not in the map when the removal
     * arrives — clearing by id there found nothing and left it reporting for
     * the rest of the collector's life.
     */
    private val listening = ConcurrentHashMap<String, T>()

    private fun listen(state: T) {
        val previous = listening.put(state.id, state)
        if (previous !== null && previous !== state) previous.mutations.listen(null)
        state.mutations.listen { mutated?.trySend(state) }
    }

    private fun unlisten(id: String) {
        listening.remove(id)?.mutations?.listen(null)
    }

    override fun setUpdateHandler(handler: (suspend (T) -> Unit)?) {
        updateHandler = handler
        watcherJob?.cancel()
        watcherJob = null
        mutated = null
        if (handler == null) return
        val channel = Channel<T>(Channel.UNLIMITED)
        mutated = channel
        watcherJob = scope.launch { watchStateChanges(channel) }
    }

    override fun replaceAll(states: List<T>) {
        val next = states.associateBy { it.id }.toMutableMap()
        for (id in listening.keys.toList()) {
            if (next[id] !== listening[id]) unlisten(id)
        }
        for (state in next.values) listen(state)
        flow.value = next
    }

    /**
     * Delivers in-place mutations that the states themselves reported.
     *
     * Reading every state to find out which changed cost the size of the
     * collection on every snapshot commit in the app, whether or not anything
     * here had changed — see [StateMutationSignal]. Now a write announces
     * itself and this only runs when one did.
     *
     * [updateDebounce] is a sampling window, not a debounce: a drag mutates the
     * same state every frame, and a debounce would keep extending its window
     * and deliver nothing until the finger stopped. The first mutation opens a
     * window, everything arriving inside it is folded in by id with the latest
     * winning, and the batch goes out when the window closes.
     */
    private suspend fun watchStateChanges(mutated: Channel<T>) {
        while (currentCoroutineContext().isActive) {
            val pending = LinkedHashMap<String, T>()
            val first = mutated.receive()
            pending[first.id] = first
            withTimeoutOrNull(updateDebounce) {
                while (true) {
                    val next = mutated.receive()
                    pending[next.id] = next
                }
            }
            val handler = updateHandler ?: return
            for (state in pending.values) handler(state)
        }
    }
}
