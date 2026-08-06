package com.mapconductor.core

import androidx.compose.runtime.snapshotFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

interface ComponentState {
    val id: String
}

/**
 * The read/write contract of an [OverlayCollector], with the fingerprint type
 * erased.
 *
 * Consumers (`CompositionLocal`s, `MarkerCollector`'s delegation, extension
 * modules such as marker clustering) only ever need `T`, so declaring them
 * against this interface keeps `FingerPrint` — an implementation detail of the
 * in-place change diff — out of every signature.
 *
 * ios-sdk / react-sdk have no equivalent interface: their `OverlayCollector`
 * needs no fingerprint type parameter, so the class alone is the API.
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
class OverlayCollector<T : ComponentState, FingerPrint>(
    private val fingerPrintOf: (T) -> FingerPrint,
    private val updateDebounce: Duration,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
) : OverlayCollectorInterface<T> {
    private val scope = scope
    private val addSharedFlow = MutableSharedFlow<T>(1000)
    private val removeSharedFlow = MutableSharedFlow<String>(1000)

    @Volatile private var updateHandler: (suspend (T) -> Unit)? = null
    private var watcherJob: Job? = null

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
        addSharedFlow.emit(state)
    }

    override fun remove(id: String) {
        removeSharedFlow.tryEmit(id)
    }

    override fun setUpdateHandler(handler: (suspend (T) -> Unit)?) {
        updateHandler = handler
        watcherJob?.cancel()
        watcherJob = null
        if (handler == null) return
        watcherJob = scope.launch { watchStateChanges() }
    }

    override fun replaceAll(states: List<T>) {
        flow.value = states.associateBy { it.id }.toMutableMap()
    }

    /**
     * Watches in-place mutations of all collected states with a single
     * [snapshotFlow] that reads every state's fingerprint, then diffs
     * successive fingerprint maps and delivers only the changed states to
     * the update handler.
     *
     * A single watcher is intentional: one watcher coroutine (and snapshot
     * apply-observer) per state does not scale — with tens of thousands of
     * states, every update-handler registration (e.g. switching map
     * providers) spawned O(n) coroutines, and every snapshot commit anywhere
     * in the app paid an O(n) observer sweep.
     *
     * [sample] — not debounce — is deliberate: a drag mutates the same state
     * every frame, and a debounce would keep extending its window and deliver
     * nothing until the finger stopped. Sampling emits the latest value once
     * per window while the change stream is still running.
     *
     * The first emission after a (re)start is recorded as the baseline and
     * not delivered: membership changes (add/remove/replaceAll) reach the
     * renderer through [flow], not the update handler.
     */
    @OptIn(FlowPreview::class)
    private suspend fun watchStateChanges() {
        flow.collectLatest { states ->
            if (states.isEmpty()) return@collectLatest
            var baseline: Map<String, FingerPrint>? = null
            snapshotFlow {
                val prints = HashMap<String, FingerPrint>(states.size * 2)
                for (state in states.values) {
                    prints[state.id] = fingerPrintOf(state)
                }
                prints
            }.sample(updateDebounce)
                .collect { current ->
                    val previous = baseline
                    baseline = current
                    if (previous == null) return@collect
                    for ((id, print) in current) {
                        if (previous[id] != print) {
                            states[id]?.let { updateHandler?.invoke(it) }
                        }
                    }
                }
        }
    }
}
