package com.mapconductor.core.marker

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import java.util.Arrays

/**
 * A hierarchical lat/lng grid over the markers, held as one sorted primitive array.
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
 * run — 0.06 ms for a tile-sized box, against 1.9 ms to scan every marker.
 *
 * The key is a Morton code over a hierarchy of cells, described in [MarkerGrid].
 * What it buys the index is that a cell at *any* level is one contiguous run,
 * so a query walks the level that suits its box rather than the finest one. The
 * first version had a single fixed cell size of 0.005 degrees and had to fall
 * back to scanning once a box touched more than 4,096 of them; choosing the
 * level removes that cliff.
 *
 * The index owns no markers. It is handed the manager's collection and takes a
 * snapshot when it rebuilds, so nothing here duplicates the map the manager
 * already keeps — an id-to-slot map of its own cost several megabytes at this
 * size, for information that already existed.
 */
@InternalMapConductorApi
internal class MarkerGridIndex<ActualMarker>(
    private val source: () -> Collection<MarkerEntityInterface<ActualMarker>>,
) {
    private var snapshot: Array<MarkerEntityInterface<ActualMarker>?> = emptyArray()

    /** Sorted `(mortonKey shl INDEX_BITS) or position-in-snapshot`. */
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

        // A box crossing the antimeridian has its east corner west of its west
        // one, and a padded box can run past ±180 outright. Both fold into a
        // single eastward sweep from the west edge.
        val lonSpan = MarkerGrid.eastwardSpan(southWest.longitude, northEast.longitude)
        val level = MarkerGrid.queryLevel(northEast.latitude - southWest.latitude, lonSpan)

        val latFrom = MarkerGrid.latCell(southWest.latitude, level)
        val latTo = MarkerGrid.latCell(northEast.latitude, level)
        if (latTo < latFrom) return emptyList()
        val (firstColumn, columnCount) = MarkerGrid.columnWalk(southWest.longitude, lonSpan, level)

        rebuildIfNeeded()
        val found = ArrayList<MarkerEntityInterface<ActualMarker>>()
        for (latCell in latFrom..latTo) {
            for (step in 0 until columnCount) {
                val key = MarkerGrid.morton(latCell, MarkerGrid.wrap(firstColumn + step, level), level)
                forEachInCell(key, level) { entity ->
                    if (bounds.contains(entity.state.position)) found.add(entity)
                }
            }
        }
        return found
    }

    /**
     * One marker for each cell the bounds touch.
     *
     * For a caller that is about to drop markers closer together than
     * [minSeparationDegrees] anyway, visiting the ones it will drop is pure
     * cost. At zoom 9 a street tree tile holds 141,221 markers and keeps 9,216;
     * reading a position off an entity costs 0.72 microseconds, so merely
     * looking at that set is 101 ms before anything is done with it.
     *
     * Walking cells instead visits the roughly 5,000 that hold anything, and
     * each one is a binary search and a single entry taken from the end of its
     * run. The level comes from the separation: the coarsest one whose cells
     * are no wider than the caller asked for.
     *
     * ## Why the winner cannot depend on the bounds
     *
     * A cell's representative is the **last entry of its run, always** — not
     * the last entry that falls inside the bounds. The difference is what a map
     * made of tiles looks like at the seams.
     *
     * Tiles are rendered one at a time, each asking for its own box grown by
     * the icon overhang. A cell straddling the boundary is asked about twice,
     * by two different boxes. Choose the winner from what is inside the box and
     * the two tiles choose **different markers:** one marker gets its left half
     * drawn on the left tile and nothing on the right, so the icon is cut down
     * the seam with no error anywhere. Measured on Tokyo's street trees, 74
     * markers at zoom 9 and 84 at zoom 10 were drawn by one tile and not by its
     * neighbour.
     *
     * Choosing without looking at the box removes the disagreement: a marker
     * whose icon reaches the next tile is inside that tile's grown box too, so
     * that tile asks about the same cell and gets the same answer.
     *
     * A returned marker may therefore lie just outside [bounds], by less than
     * one cell. The renderer clips it; what it must not do is filter the list
     * back down to the box, because that would put the disagreement back.
     *
     * Returns null when the index cannot help: a separation finer than the
     * bottom level would thin more than it asked for, and a box spanning more
     * cells than [MAX_CELLS_PER_THINNED_QUERY] is cheaper to scan.
     */
    fun inBoundsThinned(
        bounds: GeoRectBounds,
        minSeparationDegrees: Double,
    ): List<MarkerEntityInterface<ActualMarker>>? {
        val level = MarkerGrid.levelForSeparation(minSeparationDegrees) ?: return null
        val southWest = bounds.southWest ?: return null
        val northEast = bounds.northEast ?: return null

        val lonSpan = MarkerGrid.eastwardSpan(southWest.longitude, northEast.longitude)
        val latFrom = MarkerGrid.latCell(southWest.latitude, level)
        val latTo = MarkerGrid.latCell(northEast.latitude, level)
        if (latTo < latFrom) return emptyList()
        val (firstColumn, columnCount) = MarkerGrid.columnWalk(southWest.longitude, lonSpan, level)
        if ((latTo - latFrom + 1) * columnCount > MAX_CELLS_PER_THINNED_QUERY) return null

        rebuildIfNeeded()
        val shift = INDEX_BITS + 2 * (MarkerGrid.GRID_DEPTH - level)
        val found = ArrayList<MarkerEntityInterface<ActualMarker>>()
        for (latCell in latFrom..latTo) {
            for (step in 0 until columnCount) {
                val key = MarkerGrid.morton(latCell, MarkerGrid.wrap(firstColumn + step, level), level)
                val at = lowerBound(key shl shift)
                if (at >= packed.size) continue
                val limit = (key + 1) shl shift
                if (packed[at] >= limit) continue
                // The run's last entry. No containment test anywhere: that is
                // the whole point, and it is also why this is cheaper than the
                // version that scanned every border cell.
                var last = at
                while (last + 1 < packed.size && packed[last + 1] < limit) last++
                snapshot[(packed[last] and INDEX_MASK).toInt()]?.let { found.add(it) }
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

        val level = NEAREST_LEVEL
        val centreLat = MarkerGrid.latCell(position.latitude, level)
        val centreLon = MarkerGrid.lonCell(position.longitude, level)

        var best: MarkerEntityInterface<ActualMarker>? = null
        var bestDistance = Double.MAX_VALUE
        var ring = 0L

        while (ring <= MAX_NEAREST_RINGS) {
            forEachInRing(centreLat, centreLon, ring, level) { entity ->
                val distance = squaredDegrees(entity, position)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = entity
                }
            }
            // A ring is a square: the nearest unsearched point is `ring` cells
            // away on the shorter axis, which is latitude.
            val reach = ring * MarkerGrid.cellSize(level)
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
        level: Int,
        body: (MarkerEntityInterface<ActualMarker>) -> Unit,
    ) {
        // 行は極で打ち切る。折り返す経度と違い、緯度は 0..rows-1 の外に出たら
        // そこにセルは無い。inline 関数なので、ローカル関数に切り出して body を
        // 渡すことはできない -- 行の妥当性はここで直接見る。
        val rows = MarkerGrid.rows(level)
        if (ring == 0L) {
            if (centreLat in 0 until rows) {
                forEachInCell(MarkerGrid.morton(centreLat, MarkerGrid.wrap(centreLon, level), level), level, body)
            }
            return
        }
        val south = centreLat - ring
        val north = centreLat + ring
        for (offset in -ring..ring) {
            if (south in 0 until rows) {
                forEachInCell(MarkerGrid.morton(south, MarkerGrid.wrap(centreLon + offset, level), level), level, body)
            }
            if (north in 0 until rows) {
                forEachInCell(MarkerGrid.morton(north, MarkerGrid.wrap(centreLon + offset, level), level), level, body)
            }
        }
        for (offset in (-ring + 1)..(ring - 1)) {
            val row = centreLat + offset
            if (row !in 0 until rows) continue
            forEachInCell(MarkerGrid.morton(row, MarkerGrid.wrap(centreLon - ring, level), level), level, body)
            forEachInCell(MarkerGrid.morton(row, MarkerGrid.wrap(centreLon + ring, level), level), level, body)
        }
    }

    /**
     * Walks one cell at [level]. Its markers are the run whose keys share the
     * cell's prefix, which is what interleaving the bits bought.
     */
    private inline fun forEachInCell(
        key: Long,
        level: Int,
        body: (MarkerEntityInterface<ActualMarker>) -> Unit,
    ) {
        val shift = INDEX_BITS + 2 * (MarkerGrid.GRID_DEPTH - level)
        var at = lowerBound(key shl shift)
        val limit = (key + 1) shl shift
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
            keys[at] = (MarkerGrid.mortonKeyFor(position) shl INDEX_BITS) or at.toLong()
            at++
        }
        Arrays.sort(keys, 0, at)
        snapshot = taken
        packed = if (at == keys.size) keys else keys.copyOf(at)
        dirty = false
    }

    private fun lowerBound(target: Long): Int {
        var low = 0
        var high = packed.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (packed[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    // 注釈の判定はクラス単位で、Companion は別のクラスファイルになる。
    // 外側に付けただけでは空のエントリが凍結サーフェスに残る。
    @InternalMapConductorApi
    companion object {
        /**
         * The same budget for [inBoundsThinned], which earns far more per cell.
         *
         * A plain bounds query walking 20,000 cells is returning most of the
         * markers anyway, so the walk buys nothing over a scan. A thinned query
         * walking the same 20,000 returns one marker each instead of visiting
         * 141,221, and each cell costs a binary search rather than a scan.
         */
        private const val MAX_CELLS_PER_THINNED_QUERY = 1 shl 18

        /**
         * The level [nearest] walks its rings at, and the reach that buys.
         *
         * Level 15 is 0.0055 degrees on a side, matching the flat cell this
         * index used to have, so 100 rings is about 45 km as before.
         */
        private const val NEAREST_LEVEL = 15

        /** Roughly 45 km of rings before giving up and scanning. */
        private const val MAX_NEAREST_RINGS = 100

        // 24 bits of position, enough for 16.7M markers, under the cell key.
        private const val INDEX_BITS = 24
        private const val INDEX_MASK = (1L shl INDEX_BITS) - 1
    }
}
