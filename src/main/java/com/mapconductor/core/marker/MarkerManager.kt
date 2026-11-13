package com.mapconductor.core.marker

import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.geocell.HexGeocell
import com.mapconductor.core.geocell.HexGeocellInterface
import com.mapconductor.core.projection.Earth
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import android.util.Log

/**
 * Memory usage statistics for MarkerManager optimization
 */
data class MarkerManagerStats(
    val entityCount: Int,
    val hasSpatialIndex: Boolean,
    val spatialIndexInitialized: Boolean,
    val estimatedMemoryKB: Long,
)

open class MarkerManager<ActualMarker>(
    protected val geocell: HexGeocellInterface,
    val minMarkerCount: Int,
) {
    // Primary storage - single source of truth
    private val entities = ConcurrentHashMap<String, MarkerEntityInterface<ActualMarker>>()

    /**
     * The spatial index the per-tile paths use. A uniform lat/lng grid rather
     * than hex cells; see [MarkerGridIndex] for why.
     */
    private val gridIndex = MarkerGridIndex<ActualMarker> { entities.values }

    private val semaphore = ReentrantReadWriteLock()
    private var writeLock: ReentrantReadWriteLock.WriteLock? = null

    @Volatile
    private var isDestroyed = false

    fun lock() {
        if (writeLock != null) return
        writeLock =
            semaphore.writeLock().also {
                it.lock()
            }
    }

    fun unlock() {
        writeLock?.unlock()
        writeLock = null
    }

    open fun getEntity(id: String): MarkerEntityInterface<ActualMarker>? {
        if (!usable("getEntity")) return null
        return entities.get(id)
    }

    open fun hasEntity(id: String): Boolean {
        if (!usable("hasEntity")) return false
        return entities.containsKey(id)
    }

    open fun removeEntity(id: String): MarkerEntityInterface<ActualMarker>? {
        if (!usable("removeEntity")) return null
        val removed = entities.remove(id)
        if (removed != null) {
            gridIndex.remove(removed)
        }
        return removed
    }

    open fun metersPerPixel(
        position: GeoPointInterface,
        zoom: Double,
        pixels: Double,
        tileSize: Int = 256,
    ): Double {
        // 純粋な計算で内部状態を触らないため、破棄後でも警告だけ出して計算を続ける。
        usable("metersPerPixel")
        // Optimized calculation without native reflection calls
        val pixelsAtZoom = tileSize * Math.pow(2.0, zoom)
        return Earth.CIRCUMFERENCE_METERS / pixelsAtZoom * Math.cos(Math.toRadians(position.latitude)) * pixels
    }

    open fun findNearest(position: GeoPointInterface): MarkerEntityInterface<ActualMarker>? {
        if (!usable("findNearest")) return null

        if (entities.size > minMarkerCount) {
            semaphore.read { return gridIndex.nearest(position) }
        }
        // Below the threshold the grid cannot narrow anything down worth the
        // rings, so scan.
        return bruteForceNearest(position)
    }

    private fun bruteForceNearest(position: GeoPointInterface): MarkerEntityInterface<ActualMarker>? {
        semaphore.read {
            return entities.values.minByOrNull { entity ->
                val dx = entity.state.position.latitude - position.latitude
                val dy = entity.state.position.longitude - position.longitude
                dx * dx + dy * dy
            }
        }
    }

    open fun registerEntity(entity: MarkerEntityInterface<ActualMarker>) {
        if (!usable("registerEntity")) return
        semaphore.write {
            file(entities.put(entity.state.id, entity), entity)
        }
    }

    open fun updateEntity(entity: MarkerEntityInterface<ActualMarker>) {
        if (!usable("updateEntity")) return
        semaphore.write {
            // A marker that moved changes which cell it belongs to.
            file(entities.put(entity.state.id, entity), entity)
        }
    }

    /** Tells the index that [entity] took the place of [previous], if there was one. */
    private fun file(
        previous: MarkerEntityInterface<ActualMarker>?,
        entity: MarkerEntityInterface<ActualMarker>,
    ) {
        if (previous == null) gridIndex.add(entity) else gridIndex.replace(previous, entity)
    }

    open fun allEntities(): List<MarkerEntityInterface<ActualMarker>> {
        if (!usable("allEntities")) return emptyList()
        semaphore.read {
            return entities.values.toList()
        }
    }

    /**
     * Markers in [bounds], where the caller is going to drop anything closer
     * together than [minSeparationDegrees] regardless.
     *
     * The index can then answer from its cells rather than from its markers,
     * which is the difference between visiting 141,221 street trees and
     * visiting the 5,000 cells that hold them. It falls back to the full query
     * whenever it cannot honour the separation, so the caller still has to
     * apply its own rule to the result — this only promises not to return
     * markers it was told are interchangeable.
     */
    fun findMarkersInBounds(
        bounds: com.mapconductor.core.features.GeoRectBounds,
        minSeparationDegrees: Double,
    ): List<MarkerEntityInterface<ActualMarker>> {
        if (!usable("findMarkersInBounds")) return emptyList()
        if (bounds.isEmpty) return emptyList()
        if (entities.size > minMarkerCount) {
            semaphore.read {
                gridIndex.inBoundsThinned(bounds, minSeparationDegrees)?.let { return it }
            }
        }
        return findMarkersInBounds(bounds)
    }

    /**
     * Get memory usage statistics for debugging and optimization
     */
    fun getMemoryStats(): MarkerManagerStats {
        usable("getMemoryStats")
        return MarkerManagerStats(
            entityCount = entities.size,
            hasSpatialIndex = true,
            spatialIndexInitialized = gridIndex.isBuilt,
            estimatedMemoryKB = estimateMemoryUsage() / 1024,
        )
    }

    private fun estimateMemoryUsage(): Long {
        // Rough estimation in bytes
        val entityMapOverhead = entities.size * 64L // Map entry overhead + string key
        val entityObjects = entities.size * 200L // Rough entity size
        val spatialIndexSize = gridIndex.estimatedBytes()
        return entityMapOverhead + entityObjects + spatialIndexSize
    }

    open fun clear() {
        if (!usable("clear")) return
        entities.clear()
        gridIndex.invalidate()
    }

    fun findMarkersInBounds(
        bounds: com.mapconductor.core.features.GeoRectBounds,
    ): List<MarkerEntityInterface<ActualMarker>> {
        if (!usable("findMarkersInBounds")) return emptyList()
        if (bounds.isEmpty) return emptyList()

        // Only use the spatial index for larger datasets.
        if (entities.size > minMarkerCount) {
            semaphore.read { return gridIndex.inBounds(bounds) }
        }

        // Brute force filtering - simple and efficient for small to medium datasets
        return entities.values.filter { entity ->
            bounds.contains(entity.state.position)
        }
    }

    /**
     * Properly destroy resources when switching map providers
     * IMPORTANT: Call this when disposing of the MarkerManager
     */
    open fun destroy() {
        if (!isDestroyed) {
            semaphore.write {
                isDestroyed = true
                entities.clear()
            }
        }
    }

    /**
     * 破棄後のアクセスかどうかを返す。破棄後なら警告ログを出して `false` を返す。
     *
     * 以前は `IllegalStateException` を投げていたが、プロバイダ切り替えやビュー破棄の際に
     * 実行中の非同期処理（Combine/Flow の配送や進行中のレンダラ往復）が destroy 直後に
     * 到着するのは正常な競合であり、そこでクラッシュさせる理由がない。
     * destroy() は全状態をクリアするので、破棄後の操作は空状態への no-op で足りる。
     * ios-sdk / react-sdk と同じ「ログを出して無視する」セマンティクスに揃えた
     * （react-sdk の `console.warn(... ignored)` と同じく、書き込み系も実行しない）。
     */
    private fun usable(operation: String): Boolean {
        if (isDestroyed) {
            Log.w(TAG, "MarkerManager.$operation called after destroy (ignored)")
            return false
        }
        return true
    }

    protected open fun finalize() {
        destroy()
    }

    companion object {
        private const val TAG = "MapConductor"

        fun <ActualMarker> defaultManager(
            geocell: HexGeocellInterface? = null,
            minMarkerCount: Int = 2000,
        ): MarkerManager<ActualMarker> =
            MarkerManager<ActualMarker>(
                geocell = geocell ?: HexGeocell.defaultGeocell(),
                minMarkerCount = minMarkerCount,
            )
    }
}
