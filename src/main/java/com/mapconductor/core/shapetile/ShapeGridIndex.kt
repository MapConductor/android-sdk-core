package com.mapconductor.core.shapetile

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.polygon.IntList
import com.mapconductor.core.polygon.POLYGON_CELL
import com.mapconductor.core.polygon.POLYGON_MAX_CELLS
import kotlin.math.floor

/**
 * The index the shape tiles (polygons and polylines) draw from: shapes by the
 * grid cells their bounding box covers. A cell is one z12 tile
 * ([POLYGON_CELL]); a shape covering more than [POLYGON_MAX_CELLS] cells is kept
 * in a list every query checks.
 *
 * A shape is its index in the renderer's list, and [bboxOf] gives its
 * Web Mercator `minX, minY, maxX, maxY` (null for an empty slot).
 */
@InternalMapConductorApi
internal class ShapeGridIndex(
    val cells: HashMap<Long, IntArray>,
    val large: IntArray,
) {
    /**
     * The shapes whose bounding box meets the given box, each once, in
     * ascending index order -- which is draw order.
     */
    inline fun query(
        bboxOf: (Int) -> DoubleArray?,
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
    ): IntArray {
        val out = IntList()
        for (i in large) {
            val b = bboxOf(i) ?: continue
            if (b[0] <= maxX && b[2] >= minX && b[1] <= maxY && b[3] >= minY) out.add(i)
        }
        val cx0 = floor(minX / POLYGON_CELL).toLong()
        val cx1 = floor(maxX / POLYGON_CELL).toLong()
        val cy0 = floor(minY / POLYGON_CELL).toLong()
        val cy1 = floor(maxY / POLYGON_CELL).toLong()
        for (cx in cx0..cx1) {
            for (cy in cy0..cy1) {
                val inCell = cells[cellKey(cx, cy)] ?: continue
                for (i in inCell) {
                    val b = bboxOf(i) ?: continue
                    if (b[0] <= maxX && b[2] >= minX && b[1] <= maxY && b[3] >= minY) out.add(i)
                }
            }
        }
        // A shape in several cells was taken once per cell.
        return out.toSortedDistinctArray()
    }

    @InternalMapConductorApi
    companion object {
        inline fun build(
            count: Int,
            bboxOf: (Int) -> DoubleArray?,
        ): ShapeGridIndex {
            val cells = HashMap<Long, IntList>()
            val large = IntList()
            for (i in 0 until count) {
                val b = bboxOf(i) ?: continue
                val cx0 = floor(b[0] / POLYGON_CELL).toLong()
                val cx1 = floor(b[2] / POLYGON_CELL).toLong()
                val cy0 = floor(b[1] / POLYGON_CELL).toLong()
                val cy1 = floor(b[3] / POLYGON_CELL).toLong()
                if ((cx1 - cx0 + 1) * (cy1 - cy0 + 1) > POLYGON_MAX_CELLS) {
                    large.add(i)
                    continue
                }
                for (cx in cx0..cx1) {
                    for (cy in cy0..cy1) {
                        cells.getOrPut(cellKey(cx, cy)) { IntList() }.add(i)
                    }
                }
            }
            val packed = HashMap<Long, IntArray>(cells.size * 2)
            for ((key, list) in cells) packed[key] = list.toArray()
            return ShapeGridIndex(packed, large.toArray())
        }

        fun cellKey(
            cx: Long,
            cy: Long,
        ): Long = cx * 1_048_576L + cy
    }
}
