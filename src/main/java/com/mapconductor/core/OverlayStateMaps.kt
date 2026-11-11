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
