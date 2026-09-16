package com.mapconductor.core

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Where a [ComponentState] reports that one of its own fields was written.
 *
 * The collector used to find these writes by reading every state's fingerprint
 * inside one `snapshotFlow`. That reads a state object per field per state, and
 * Compose asks a `snapshotFlow`'s apply observer, on **every** snapshot commit
 * anywhere in the app, whether any of the objects it read is among the changed
 * ones — a `contains` per object read. With Tokyo's 144,183 street trees that
 * is about 1.7 million probes, and a drag commits a snapshot every frame: 270
 * to 380 ms of the main thread per frame on a Pixel 5a, measured, against 20 ms
 * with the watcher switched off.
 *
 * The loop runs the wrong way round. Asking "of everything I read, did any of
 * it change?" costs the size of the collection; being told "this one changed"
 * costs one call per actual change, and during a drag markers do not change at
 * all. So the states push instead.
 */
class StateMutationSignal {
    @Volatile
    private var listener: (() -> Unit)? = null

    /**
     * Sets who hears about writes, or clears it with null.
     *
     * A state belongs to at most one collector, so there is one listener rather
     * than a list.
     */
    internal fun listen(listener: (() -> Unit)?) {
        this.listener = listener
    }

    /** Reports a write. Called from whatever thread performed it. */
    fun notifyMutated() {
        listener?.invoke()
    }

    /**
     * A [MutableState] holding [initial] that reports its writes to this signal.
     *
     * Use in place of `mutableStateOf` for every field of a [ComponentState]
     * that callers may change after the state is collected:
     *
     * ```
     * override val mutations = StateMutationSignal()
     * var icon: MarkerIconInterface? by mutations.notifying(icon)
     * ```
     *
     * Declare `mutations` above the fields that use it: property initialisers
     * run in declaration order, and the other way round it is still null here.
     */
    fun <T> notifying(initial: T): MutableState<T> = NotifyingState(mutableStateOf(initial), this)
}

/**
 * Writes through to [inner] and reports the write, so the field stays readable
 * from a composition exactly as a plain `mutableStateOf` field would be.
 *
 * Only a real change is reported. `mutableStateOf` already compares with
 * structural equality before waking its own readers, so nothing new is spent
 * here, and the collector's old fingerprint diff ignored equal values too.
 */
private class NotifyingState<T>(
    private val inner: MutableState<T>,
    private val signal: StateMutationSignal,
) : MutableState<T> {
    override var value: T
        get() = inner.value
        set(value) {
            if (inner.value == value) return
            inner.value = value
            signal.notifyMutated()
        }

    override fun component1(): T = value

    override fun component2(): (T) -> Unit = { value = it }
}
