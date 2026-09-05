package com.mapconductor.core.marker

import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.geocell.HexCell
import com.mapconductor.core.geocell.HexCellRegistry
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
     * The spatial index the per-tile paths use.
     *
     * A uniform grid rather than the hex-cell registry below: that one is built
     * at a fixed zoom of 20, which puts a cell at about 12 cm, so every marker
     * lands in its own and it groups nothing. See [MarkerGridIndex].
     */
    private val gridIndex = MarkerGridIndex<ActualMarker> { entities.values }

    /**
     * Kept only for [findByIdPrefix], which hands out HexCells and has no
     * caller inside the SDK. Built the first time that is asked for, so nothing
     * pays for it otherwise.
     */
    @Volatile
    private var cellRegistry: HexCellRegistry<ActualMarker>? = null

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
            gridIndex.invalidate()
            // Only update the hex registry if something asked for one.
            cellRegistry?.removePoint(removed)
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

    open fun findByIdPrefix(prefix: String): List<HexCell> {
        if (!usable("findByIdPrefix")) return emptyList()
        // The registry is built here and nowhere else: this is the one caller
        // that needs hex cells rather than markers.
        return ensureCellRegistry().findByIdPrefix(prefix)
    }

    open fun registerEntity(entity: MarkerEntityInterface<ActualMarker>) {
        if (!usable("registerEntity")) return
        semaphore.write {
            entities[entity.state.id] = entity
            gridIndex.invalidate()
            // Only update the hex registry if something asked for one.
            cellRegistry?.setPoint(entity)
        }
    }

    /**
     * Lazy-initialize the spatial index only when spatial operations are needed.
     * Double-checked locking: the registry is assigned only after it is fully populated,
     * preventing concurrent callers from observing an empty non-null registry.
     */
    private fun ensureCellRegistry(): HexCellRegistry<ActualMarker> {
        cellRegistry?.let { return it }
        return semaphore.write {
            cellRegistry ?: run {
                val registry = HexCellRegistry<ActualMarker>(geocell = geocell, zoom = 20.0)
                entities.values.forEach { entity -> registry.setPoint(entity) }
                cellRegistry = registry
                registry
            }
        }
    }

    open fun updateEntity(entity: MarkerEntityInterface<ActualMarker>) {
        if (!usable("updateEntity")) return
        semaphore.write {
            entities[entity.state.id] = entity
            // A marker that moved changes which cell it belongs to.
            gridIndex.invalidate()
            // Only update the hex registry if something asked for one.
            cellRegistry?.setPoint(entity)
        }
    }

    open fun allEntities(): List<MarkerEntityInterface<ActualMarker>> {
        if (!usable("allEntities")) return emptyList()
        semaphore.read {
            return entities.values.toList()
        }
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
        val gridSize = gridIndex.estimatedBytes()
        // The hex registry is usually absent; when findByIdPrefix has built it,
        // it costs a cell object and a string id per marker.
        val hexSize = if (cellRegistry != null) entities.size * 100L else 0L
        val spatialIndexSize = gridSize + hexSize
        return entityMapOverhead + entityObjects + spatialIndexSize
    }

    open fun clear() {
        if (!usable("clear")) return
        entities.clear()
        gridIndex.invalidate()
        cellRegistry?.clear()
    }

    fun findMarkersInBounds(
        bounds: com.mapconductor.core.features.GeoRectBounds,
    ): List<MarkerEntityInterface<ActualMarker>> {
        if (!usable("findMarkersInBounds")) return emptyList()
        if (bounds.isEmpty) return emptyList()

        // For spatial queries, ensure the cell registry is initialized.
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
                cellRegistry?.clear()
                cellRegistry = null
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
