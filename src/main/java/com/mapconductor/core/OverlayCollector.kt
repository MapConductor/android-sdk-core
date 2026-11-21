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
import kotlinx.coroutines.flow.update
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
     *
     * Only an assignment to a field is reported. Changing the object a field
     * holds — adding to a list passed in as `points`, say — reaches no one, and
     * assigning a value equal to the current one is ignored, so re-assigning a
     * list after editing it in place does nothing either. Assign a new value.
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
@InternalMapConductorApi
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

    /**
     * Direct adds waiting for their debounced membership publication.
     *
     * Membership reaches [flow] through a debounce, so a state added and
     * removed inside the same window is not in the map when the removal
     * arrives; it is found here instead. Together with [published] this is
     * every state this collector listens to, without a second map of the whole
     * collection (with 720k circles that map alone was 28 MB).
     */
    private val pendingAdds = ConcurrentHashMap<String, T>()

    /** The membership [listenToWrittenStates] last listened to. */
    @Volatile private var published: Map<String, T> = emptyMap()

    /**
     * What this collector listens through, replaced at every
     * [listenToWrittenStates] pass. A state's listener says whether it reports
     * here (a [PassToken] of this collector) and whether this pass has seen it
     * (the current one): telling which states left a bulk list takes one read
     * per state, not a lookup of each id in the other map.
     */
    @Volatile private var token = PassToken(this)

    @InternalMapConductorApi
    private class PassToken(
        val collector: OverlayCollector<*>,
    ) : StateMutationListener {
        override fun onMutated(state: ComponentState) = collector.onMutated(state, this)
    }

    /** Each state of [states] with its id, walking an [OverlayStateList]'s list rather than its index. */
    private inline fun forEachState(
        states: Map<String, T>,
        action: (id: String, state: T) -> Unit,
    ) {
        if (states is OverlayStateList<*>) {
            @Suppress("UNCHECKED_CAST")
            (states as OverlayStateList<T>).forEachState { action(it.id, it) }
        } else {
            for ((id, state) in states) action(id, state)
        }
    }

    private fun tokenOf(state: T): PassToken? =
        (state.mutations.currentListener as? PassToken)?.takeIf { it.collector === this }

    init {
        scope.launch {
            addSharedFlow.debounceBatch(5.milliseconds, 100).collect { states ->
                flow.update { current ->
                    copyOverlayStates(current).apply {
                        states.forEach { state -> this[state.id] = state }
                    }
                }
            }
        }

        scope.launch {
            removeSharedFlow.debounceBatch(5.milliseconds, 300).collect { ids ->
                flow.update { current ->
                    copyOverlayStates(current).apply {
                        ids.forEach { id -> remove(id) }
                    }
                }
            }
        }

        // [flow] is writable from outside, and the compose `Markers(list)` writes
        // its whole list straight into it rather than calling [add] 144k times.
        // Those states were never listened to, so an in-place change to one of
        // them -- an icon swapped to relabel a street tree -- reached nobody, and
        // the tiles kept drawing the old icon. Listen to whatever arrives this
        // way too. Runs per membership change, not per mutation.
        scope.launch {
            flow.collect { states -> listenToWrittenStates(states) }
        }
    }

    private fun listenToWrittenStates(states: Map<String, T>) {
        val previous = published
        published = states
        val pass = PassToken(this)
        token = pass
        val anyPending = pendingAdds.isNotEmpty()
        forEachState(states) { id, state ->
            if (anyPending) {
                val pending = pendingAdds.remove(id)
                if (pending !== null && pending !== state) stop(pending)
            }
            // Already reporting here: whatever held its id before was stopped
            // when it started. Most of a bulk list is, from the last pass.
            if (tokenOf(state) == null) {
                val old = previous[id]
                if (old !== null && old !== state) stop(old)
            }
            state.mutations.listen(pass, state)
        }
        // What the last pass listened to and this one did not see has left.
        // Only stop what this path started: a state added through [add] may not
        // have reached [flow] yet (membership is debounced).
        val stillPending = pendingAdds.isNotEmpty()
        forEachState(previous) { id, state ->
            val heard = tokenOf(state)
            if (heard != null && heard !== pass && (!stillPending || pendingAdds[id] !== state)) {
                state.mutations.stopListening(heard)
            }
        }
    }

    override suspend fun add(state: T) {
        val previous = pendingAdds.put(state.id, state) ?: published[state.id]
        if (previous !== null && previous !== state) stop(previous)
        listen(state)
        addSharedFlow.emit(state)
    }

    override fun remove(id: String) {
        pendingAdds.remove(id)?.let(::stop)
        published[id]?.let(::stop)
        removeSharedFlow.tryEmit(id)
    }

    private fun listen(state: T) {
        state.mutations.listen(token, state)
    }

    private fun stop(state: T) {
        tokenOf(state)?.let { state.mutations.stopListening(it) }
    }

    /**
     * A write to a state this collector listens to. Delivered only while the
     * state is still a member (published, or added and waiting to be): a
     * state left listening by a membership change this collector never saw
     * (a [flow] value replaced before it was collected) is dropped here.
     */
    private fun onMutated(
        state: ComponentState,
        via: PassToken,
    ) {
        val channel = mutated ?: return
        val id = state.id
        // Heard through the latest pass's token: the latest published membership
        // has it. Otherwise look it up (pending adds, a newer flow value).
        if (via !== token && pendingAdds[id] !== state && flow.value[id] !== state && published[id] !== state) return
        @Suppress("UNCHECKED_CAST")
        channel.trySend(state as T)
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
        val next = copyOverlayStates<T>(emptyMap())
        for (state in states) next[state.id] = state
        for (map in listOf(published, flow.value, pendingAdds)) {
            for ((id, state) in map) if (next[id] !== state) stop(state)
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
