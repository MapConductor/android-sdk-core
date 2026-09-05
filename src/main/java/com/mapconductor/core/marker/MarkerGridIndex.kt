package com.mapconductor.core.marker

import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import java.util.Arrays

/**
 * A uniform lat/lng grid over the markers, held as one sorted primitive array.
 *
 * Replaces the hex-cell registry and its kd-tree on the paths that run per
 * tile. That index was built at a fixed zoom of 20, which puts a cell at about
 * 12 cm — street trees are metres apart, so every marker landed in its own cell
 * and the index grouped nothing. It cost a HexCell object and a String id per
 * marker, a pair of concurrent maps and a kd-tree node each; on Tokyo's 144,183
 * street trees, building it aborted the process with a native out-of-memory.
 *
 * Here each marker is one Long: its cell key above its position in a snapshot.
 * Building the index is an arithmetic pass and a primitive sort, measured at
 * 41 ms for those same 144,183 markers on a Pixel 5a, in 1.15 MB.
 *
 * A bounds query walks the cells the box covers and binary-searches each one's
 * run — 0.06 ms for a tile-sized box, against 1.9 ms to scan every marker. When
 * the box is wide enough to touch more cells than [MAX_CELLS_PER_QUERY], it
 * scans instead: an index that cannot narrow anything down is slower than not
 * having one, and the same measurement shows where the crossover falls.
 *
 * The index owns no markers. It is handed the manager's collection and takes a
 * snapshot when it rebuilds, so nothing here duplicates the map the manager
 * already keeps — an id-to-slot map of its own cost several megabytes at this
 * size, for information that already existed.
 */
internal class MarkerGridIndex<ActualMarker>(
    private val source: () -> Collection<MarkerEntityInterface<ActualMarker>>,
) {
    private var snapshot: Array<MarkerEntityInterface<ActualMarker>?> = emptyArray()

    /** Sorted `(cellKey shl INDEX_BITS) or position-in-snapshot`. */
    private var packed = LongArray(0)

    @Volatile
    private var dirty = true

    /** Whether the index currently holds a built snapshot. */
    val isBuilt: Boolean
        get() = !dirty

    /** Roughly what the index costs: two longs a marker, key and reference. */
    fun estimatedBytes(): Long = packed.size * 8L + snapshot.size * 8L

    /** Marks the index stale. The rebuild happens on the next query. */
    fun invalidate() {
        dirty = true
    }

    /** Markers whose position falls inside [bounds]. */
    fun inBounds(bounds: GeoRectBounds): List<MarkerEntityInterface<ActualMarker>> {
        val southWest = bounds.southWest ?: return emptyList()
        val northEast = bounds.northEast ?: return emptyList()

        val latFrom = Math.floor(southWest.latitude / CELL_DEGREES).toLong()
        val latTo = Math.floor(northEast.latitude / CELL_DEGREES).toLong()
        val lonFrom = Math.floor(southWest.longitude / CELL_DEGREES).toLong()
        val lonTo = Math.floor(northEast.longitude / CELL_DEGREES).toLong()

        // A box crossing the antimeridian has its east corner west of its west
        // one. Walking to the unwrapped end and folding each column back onto
        // the globe covers both halves without a second loop — and without the
        // empty range that silently returned no markers there.
        val lonEnd = if (northEast.longitude < southWest.longitude) lonTo + LON_CELLS else lonTo

        if ((latTo - latFrom + 1) * (lonEnd - lonFrom + 1) > MAX_CELLS_PER_QUERY) {
            return source().filter { bounds.contains(it.state.position) }
        }

        rebuildIfNeeded()
        val found = ArrayList<MarkerEntityInterface<ActualMarker>>()
        for (latCell in latFrom..latTo) {
            for (lonCell in lonFrom..lonEnd) {
                forEachInCell(cellKeyOf(latCell, wrapLon(lonCell))) { entity ->
                    if (bounds.contains(entity.state.position)) found.add(entity)
                }
            }
        }
        return found
    }

    /**
     * The marker nearest [position], by squared degrees.
     *
     * Rings of cells are searched outward from the one holding the point. The
     * stopping rule is a distance, not a ring count: a ring is a square, so a
     * hit in one of its corners sits about 1.4 cells further out than a hit on
     * its edge, and stopping a fixed ring after the first hit returns the wrong
     * marker. Having finished ring r, everything still unsearched is at least r
     * cells away, so the search ends once that already exceeds the best
     * distance found.
     */
    fun nearest(position: GeoPointInterface): MarkerEntityInterface<ActualMarker>? {
        rebuildIfNeeded()
        if (packed.isEmpty()) return null

        val centreLat = Math.floor(position.latitude / CELL_DEGREES).toLong()
        val centreLon = Math.floor(position.longitude / CELL_DEGREES).toLong()

        var best: MarkerEntityInterface<ActualMarker>? = null
        var bestDistance = Double.MAX_VALUE
        var ring = 0L

        while (ring <= MAX_NEAREST_RINGS) {
            forEachInRing(centreLat, centreLon, ring) { entity ->
                val distance = squaredDegrees(entity, position)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = entity
                }
            }
            val reach = ring * CELL_DEGREES
            if (best != null && reach * reach >= bestDistance) break
            ring++
        }

        // Nothing within the rings searched: the set is sparse enough here that
        // scanning is cheaper than widening further.
        return best ?: source().minByOrNull { squaredDegrees(it, position) }
    }

    private fun squaredDegrees(
        entity: MarkerEntityInterface<ActualMarker>,
        position: GeoPointInterface,
    ): Double {
        val deltaLat = entity.state.position.latitude - position.latitude
        val deltaLon = entity.state.position.longitude - position.longitude
        return deltaLat * deltaLat + deltaLon * deltaLon
    }

    private inline fun forEachInRing(
        centreLat: Long,
        centreLon: Long,
        ring: Long,
        body: (MarkerEntityInterface<ActualMarker>) -> Unit,
    ) {
        if (ring == 0L) {
            forEachInCell(cellKeyOf(centreLat, centreLon), body)
            return
        }
        for (offset in -ring..ring) {
            forEachInCell(cellKeyOf(centreLat - ring, wrapLon(centreLon + offset)), body)
            forEachInCell(cellKeyOf(centreLat + ring, wrapLon(centreLon + offset)), body)
        }
        for (offset in (-ring + 1)..(ring - 1)) {
            forEachInCell(cellKeyOf(centreLat + offset, wrapLon(centreLon - ring)), body)
            forEachInCell(cellKeyOf(centreLat + offset, wrapLon(centreLon + ring)), body)
        }
    }

    private inline fun forEachInCell(
        key: Long,
        body: (MarkerEntityInterface<ActualMarker>) -> Unit,
    ) {
        var at = lowerBound(key shl INDEX_BITS)
        val limit = (key + 1) shl INDEX_BITS
        while (at < packed.size && packed[at] < limit) {
            snapshot[(packed[at] and INDEX_MASK).toInt()]?.let(body)
            at++
        }
    }

    @Synchronized
    private fun rebuildIfNeeded() {
        if (!dirty) return
        val entities = source()
        val taken = arrayOfNulls<MarkerEntityInterface<ActualMarker>>(entities.size)
        val keys = LongArray(entities.size)
        var at = 0
        for (entity in entities) {
            if (at == taken.size) break // grew while we were reading; the next query rebuilds
            val position = entity.state.position
            taken[at] = entity
            keys[at] = (cellKeyFor(position) shl INDEX_BITS) or at.toLong()
            at++
        }
        Arrays.sort(keys, 0, at)
        snapshot = taken
        packed = if (at == keys.size) keys else keys.copyOf(at)
        dirty = false
    }

    private fun cellKeyFor(position: GeoPointInterface): Long =
        cellKeyOf(
            Math.floor(position.latitude / CELL_DEGREES).toLong(),
            Math.floor(position.longitude / CELL_DEGREES).toLong(),
        )

    private fun lowerBound(target: Long): Int {
        var low = 0
        var high = packed.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (packed[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        /**
         * About 450 m at Tokyo's latitude.
         *
         * Chosen by measurement rather than by round number: on 144k markers a
         * tile-sized query took 0.06 ms here, 0.03 ms at 0.001 degrees and
         * 2.31 ms at 0.02 degrees — the last being no better than scanning,
         * because a cell that size returns five times the markers a tile needs.
         * Finer wins on dense data and loses on sparse, where a tile spans more
         * empty cells than it saves.
         */
        private const val CELL_DEGREES = 0.005

        /**
         * Past this many cells, scan every marker instead.
         *
         * A query covering most of the world touches more empty cells than
         * there are markers. Measured on the same 144k: a box over all of Tokyo
         * costs 2.27 ms through the grid and 1.98 ms scanning, so the index
         * stops paying for itself well before the pathological case.
         */
        private const val MAX_CELLS_PER_QUERY = 4096

        /** Roughly 45 km of rings before giving up and scanning. */
        private const val MAX_NEAREST_RINGS = 100

        /** Columns around the globe: the wrap the ring and box walks fold on. */
        private val LON_CELLS = (360.0 / CELL_DEGREES).toLong()
        private val MIN_LON_CELL = -LON_CELLS / 2

        private fun wrapLon(lonCell: Long): Long = MIN_LON_CELL + Math.floorMod(lonCell - MIN_LON_CELL, LON_CELLS)

        // 24 bits of position, enough for 16.7M markers, under the cell key.
        private const val INDEX_BITS = 24
        private const val INDEX_MASK = (1L shl INDEX_BITS) - 1

        // Offsets keep keys positive, so their ordering matches the numeric
        // ordering the sort and the binary search depend on.
        private fun cellKeyOf(
            latCell: Long,
            lonCell: Long,
        ): Long = ((latCell + 262144) shl 20) or (lonCell + 524288)
    }
}
