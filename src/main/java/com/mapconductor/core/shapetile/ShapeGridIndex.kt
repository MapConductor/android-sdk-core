package com.mapconductor.core.shapetile

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.polygon.IntList
import com.mapconductor.core.polygon.POLYGON_CELL
import com.mapconductor.core.polygon.POLYGON_MAX_CELLS
import java.util.BitSet
import kotlin.math.floor

/** A multiresolution grid: wide shapes use coarser cells, not a global scan list. */
@InternalMapConductorApi
internal class ShapeGridIndex private constructor(
    val cells: HashMap<Long, IntArray>,
    /** Shapes that formerly needed a global scan; retained for diagnostics. */
    val large: IntArray,
    private val levels: List<Level>,
    private val count: Int,
    private val legacyLarge: IntArray = IntArray(0),
) {
    @InternalMapConductorApi
    private class Level(
        val size: Double,
        val cells: HashMap<Long, IntArray>,
        val groups: HashMap<Long, Array<IntArray>>? = null,
    )

    @InternalMapConductorApi
    constructor(cells: HashMap<Long, IntArray>, large: IntArray) : this(
        cells, large, listOf(Level(POLYGON_CELL, cells)),
        maxOf(cells.values.maxOfOrNull { it.maxOrNull() ?: -1 } ?: -1, large.maxOrNull() ?: -1) + 1,
        large,
    )

    fun query(
        bboxOf: (Int) -> DoubleArray?,
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
    ): IntArray {
        val seen = BitSet(count)
        val matches = BitSet(count)

        fun take(i: Int) {
            seen.set(i)
        }
        for (i in legacyLarge) take(i)
        for (level in levels) {
            val cx0 = floor(minX / level.size).toLong()
            val cx1 = floor(maxX / level.size).toLong()
            val cy0 = floor(minY / level.size).toLong()
            val cy1 = floor(maxY / level.size).toLong()
            val area = (cx1 - cx0 + 1).toDouble() * (cy1 - cy0 + 1)
            val groups = level.groups
            if (area > 16_384 && groups != null) {
                val gx0 = Math.floorDiv(cx0, 16L)
                val gx1 = Math.floorDiv(cx1, 16L)
                val gy0 = Math.floorDiv(cy0, 16L)
                val gy1 = Math.floorDiv(cy1, 16L)
                val groupArea = (gx1 - gx0 + 1).toDouble() * (gy1 - gy0 + 1)
                if (groupArea > groups.size) {
                    for (bucket in level.cells.values) for (i in bucket) take(i)
                } else {
                    for (gx in gx0..gx1) {
                        for (gy in gy0..gy1) {
                            val buckets = groups[cellKey(gx, gy)] ?: continue
                            for (bucket in buckets) for (i in bucket) take(i)
                        }
                    }
                }
            } else if (area > level.cells.size) {
                // A zoomed-out tile must not enumerate millions of empty cells.
                for (bucket in level.cells.values) for (i in bucket) take(i)
            } else {
                for (cx in cx0..cx1) {
                    for (cy in cy0..cy1) {
                        val bucket = level.cells[cellKey(cx, cy)] ?: continue
                        for (i in bucket) take(i)
                    }
                }
            }
        }
        // Read boxes in source order. File-backed geometry can then stream
        // through its block cache instead of seeking in spatial bucket order.
        var candidate = seen.nextSetBit(0)
        while (candidate >= 0) {
            val b = bboxOf(candidate)
            if (b != null && b[0] <= maxX && b[2] >= minX && b[1] <= maxY && b[3] >= minY) matches.set(candidate)
            candidate = seen.nextSetBit(candidate + 1)
        }
        // A bitset removes duplicates before bounding-box checks and produces
        // draw order without sorting the same shape once for every cell it spans.
        val out = IntArray(matches.cardinality())
        var next = matches.nextSetBit(0)
        var at = 0
        while (next >= 0) {
            out[at++] = next
            next = matches.nextSetBit(next + 1)
        }
        return out
    }

    @InternalMapConductorApi
    companion object {
        fun build(
            count: Int,
            bboxOf: (Int) -> DoubleArray?,
        ): ShapeGridIndex {
            val buckets = Array(7) { HashMap<Long, IntList>() }
            val canGroup = BooleanArray(7) { true }
            val large = IntList()
            for (i in 0 until count) {
                val b = bboxOf(i) ?: continue
                var level = 0
                var size = POLYGON_CELL

                fun cellsWide() =
                    (floor(b[2] / size) - floor(b[0] / size) + 1) *
                        (floor(b[3] / size) - floor(b[1] / size) + 1)
                if (cellsWide() > POLYGON_MAX_CELLS) large.add(i)
                while (level < buckets.lastIndex && cellsWide() > 16) {
                    level++
                    size *= 4
                }
                val cx0 = floor(b[0] / size).toLong()
                val cx1 = floor(b[2] / size).toLong()
                val cy0 = floor(b[1] / size).toLong()
                val cy1 = floor(b[3] / size).toLong()
                // Group keys can be decoded only when Y is in the packed key's
                // nonnegative range. Keep the original query for polar overhangs.
                if (cy0 < 0 || cy1 >= 1_048_576L) canGroup[level] = false
                for (cx in cx0..cx1) {
                    for (cy in cy0..cy1) {
                        buckets[level].getOrPut(cellKey(cx, cy)) { IntList() }.add(i)
                    }
                }
            }
            val levels =
                buckets.mapIndexed { level, map ->
                    val packed = HashMap<Long, IntArray>(map.size * 2)
                    for ((key, list) in map) packed[key] = list.toArray()
                    val groups =
                        if (canGroup[level]) {
                            val grouped = HashMap<Long, MutableList<IntArray>>()
                            for ((key, values) in packed) {
                                val cx = Math.floorDiv(key, 1_048_576L)
                                val cy = Math.floorMod(key, 1_048_576L)
                                grouped
                                    .getOrPut(cellKey(Math.floorDiv(cx, 16L), Math.floorDiv(cy, 16L))) { ArrayList() }
                                    .add(values)
                            }
                            HashMap<Long, Array<IntArray>>().also { out ->
                                for ((key, values) in grouped) {
                                    out[key] =
                                        values.toTypedArray()
                                }
                            }
                        } else {
                            null
                        }
                    Level(POLYGON_CELL * (1 shl (level * 2)), packed, groups)
                }
            return ShapeGridIndex(levels[0].cells, large.toArray(), levels.filter { it.cells.isNotEmpty() }, count)
        }

        fun cellKey(
            cx: Long,
            cy: Long,
        ): Long = cx * 1_048_576L + cy
    }
}
