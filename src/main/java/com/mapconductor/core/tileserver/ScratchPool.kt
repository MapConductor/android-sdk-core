package com.mapconductor.core.tileserver

/**
 * A few reusable scratch objects (pixel buffers, bitmaps, output streams) for
 * code that renders tiles on threads it does not own.
 *
 * Use this instead of a `ThreadLocal`. Tile callbacks run on the map SDK's
 * threads, and some SDKs start new ones for every map and never stop them:
 * Google Maps keeps an `androidmapsapi-tileoverlay` pool of three live threads
 * per map it ever created. A thread-local scratch pins one object on each such
 * thread for good, so every map switch added a few megabytes that nothing
 * could free. A pool holds at most [capacity] objects whatever the threads do.
 *
 * [acquire] hands out a pooled object or null (the caller makes a fresh one);
 * [release] takes it back, or drops it when the pool is full.
 */
internal class ScratchPool<T : Any>(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val items = ArrayDeque<T>(capacity)

    /** A pooled object that [accept] takes, or null. Others are dropped. */
    fun acquire(accept: (T) -> Boolean = { true }): T? {
        synchronized(items) {
            while (items.isNotEmpty()) {
                val item = items.removeLast()
                if (accept(item)) return item
            }
        }
        return null
    }

    /** Returns [item]. False if the pool was full and did not keep it. */
    fun release(item: T): Boolean =
        synchronized(items) {
            if (items.size >= capacity) return false
            items.addLast(item)
            true
        }

    /** Pooled objects right now. For tests. */
    val size: Int
        get() = synchronized(items) { items.size }

    companion object {
        /** About how many tiles are drawn at the same time. */
        val DEFAULT_CAPACITY: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }
}
