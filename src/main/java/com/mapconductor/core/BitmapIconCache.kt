package com.mapconductor.core

import androidx.annotation.Keep
import com.mapconductor.core.marker.BitmapIcon
import android.util.LruCache

object BitmapIconCache {
    // Guards both counts and bitmapCache: entries are read/written from the
    // tile-server worker threads (MarkerTileRenderer.renderTile) as well as
    // the main thread.
    private val lock = Any()

    private val counts: HashMap<Int, Int> = HashMap()

    private val bitmapCache: LruCache<Int, BitmapIcon> by lazy {
        // Get max memory size by bytes
        val maxMemory = Runtime.getRuntime().maxMemory()
        val cacheSize = maxMemory / 8

        // Cache bytes
        object : LruCache<Int, BitmapIcon>(cacheSize.toInt()) {
            override fun sizeOf(
                key: Int,
                iconRes: BitmapIcon,
            ): Int = iconRes.bitmap.byteCount

            override fun entryRemoved(
                evicted: Boolean,
                key: Int,
                oldValue: BitmapIcon,
                newValue: BitmapIcon?,
            ) {
                // Keep the refcount map in sync with size-based evictions;
                // otherwise put() would skip re-inserting the bitmap forever
                // (refCount > 0) while get() keeps returning null.
                if (evicted) {
                    synchronized(lock) {
                        counts.remove(key)
                    }
                }
            }
        }
    }

    fun put(
        id: Int,
        bitmapIcon: BitmapIcon,
    ) {
        synchronized(lock) {
            val refCount = counts.getOrDefault(id, 0)
            counts.put(id, refCount + 1)
            if (refCount == 0) {
                bitmapCache.put(id, bitmapIcon)
            }
        }
    }

    fun refCountUp(id: Int) {
        synchronized(lock) {
            if (!counts.contains(id)) return
            val refCount = counts.getOrDefault(id, 0)
            counts.put(id, refCount + 1)
        }
    }

    fun get(id: Int): BitmapIcon? {
        synchronized(lock) {
            if (!counts.contains(id)) return null
            return bitmapCache.get(id)
        }
    }

    fun refCountDown(id: Int) {
        synchronized(lock) {
            if (!counts.contains(id)) return
            val refCount = counts.getOrDefault(id, 1) - 1
            if (refCount == 0) {
                counts.remove(id)
                bitmapCache.remove(id)
                return
            }
            counts.put(id, refCount)
        }
    }

    @Keep
    fun clear() {
        synchronized(lock) {
            counts.clear()
            bitmapCache.evictAll()
        }
    }
}
