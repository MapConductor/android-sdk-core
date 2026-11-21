package com.mapconductor.core

import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.persistentHashSetOf

/**
 * A new writable generation, sharing unchanged entries with the published map.
 * Finish writing before publishing; never mutate a published generation.
 * Keeps the collector's MutableMap contract and insertion order.
 */
@InternalMapConductorApi
fun <T> copyOverlayStates(current: Map<String, T>): MutableMap<String, T> =
    synchronized(current) { current.toPersistentMap().builder() }

/** Compact membership snapshot without a linked hash entry for every state. */
@InternalMapConductorApi
fun overlayStateIds(states: Iterable<ComponentState>): Set<String> =
    persistentHashSetOf<String>().builder().apply {
        for (state in states) add(state.id)
    }.build()

/**
 * A collector's membership handed over as the list a collection was given,
 * for collections of hundreds of thousands of states: building and walking a
 * map of them cost seconds per hand-over, and the readers that matter (the
 * collector and the overlays) only walk the states in order.
 *
 * [states] is the list itself. Looking a key up builds an index on first use.
 * Like every published generation it is not to be written to; a write goes to
 * the index and [states] then follows the index.
 *
 * The ids in [list] must be distinct: [states] and [forEachState] do not
 * deduplicate.
 */
@InternalMapConductorApi
class OverlayStateList<T : ComponentState>(
    private val list: List<T>,
) : AbstractMutableMap<String, T>() {
    @Volatile private var index: LinkedHashMap<String, T>? = null

    @Volatile private var written = false

    private fun index(): LinkedHashMap<String, T> =
        index ?: synchronized(this) {
            index ?: LinkedHashMap<String, T>((list.size / 0.75f).toInt() + 1).also { map ->
                for (state in list) map[state.id] = state
                index = map
            }
        }

    /** The states in order: the list handed over, unless this map was written to. */
    fun states(): List<T> = if (written) ArrayList(index().values) else list

    /** Calls [action] for each state in order, without building the index. */
    inline fun forEachState(action: (T) -> Unit) {
        for (state in states()) action(state)
    }

    /** The ids, through the index. */
    val ids: Set<String> get() = index().keys

    override val size: Int get() = if (written) index().size else list.size

    override val entries: MutableSet<MutableMap.MutableEntry<String, T>> get() = index().entries

    override fun get(key: String): T? = index()[key]

    override fun containsKey(key: String): Boolean = index().containsKey(key)

    override fun put(
        key: String,
        value: T,
    ): T? {
        written = true
        return index().put(key, value)
    }

    override fun remove(key: String): T? {
        written = true
        return index().remove(key)
    }

    override fun clear() {
        written = true
        index().clear()
    }
}
