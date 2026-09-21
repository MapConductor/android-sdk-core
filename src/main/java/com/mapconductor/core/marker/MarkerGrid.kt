package com.mapconductor.core.marker

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPointInterface

/**
 * The cell arithmetic behind [MarkerGridIndex], with no marker in sight.
 *
 * Kept apart from the index because none of it depends on what a marker is: it
 * is a way of naming a patch of the globe, and the conformance fixtures the
 * three SDKs share test it on its own.
 *
 * ## The scheme
 *
 * The world starts as **two square cells** — the western and eastern halves,
 * 180 degrees on a side — and each is halved in latitude and in longitude,
 * repeatedly. The key records the half chosen each time, latitude bit above
 * longitude bit, under the bit that says which hemisphere it started in. That
 * makes **every prefix of a key a cell:** dropping the low `2n` bits names the
 * cell `n` levels up, and all of its children share the prefix, so they form
 * one run in a sorted array. A query picks the level that suits its box
 * instead of paying the finest one.
 *
 * The first version packed `(latCell shl 20) or lonCell` at one fixed cell size
 * of 0.005 degrees. That key sorts by row, so a cell is a contiguous run but a
 * *block* of cells is not, and there was no way to ask a coarser question.
 *
 * ## Why two root cells and not one
 *
 * One root cell covering the whole globe is what cordova-plugin-googlemaps'
 * `geomodel.js` uses, and it is what this started as. It makes every cell
 * **twice as wide as it is tall**, because the same number of divisions covers
 * 360 degrees of longitude and 180 of latitude.
 *
 * That costs real time. A query asks for cells no wider than some separation;
 * with a 2:1 cell, satisfying the width over-resolves the height by a factor of
 * two, so the query walks twice the cells and returns twice the markers it
 * asked for. Measured on ios-sdk with Tokyo's 144,183 street trees at zoom 11,
 * a tile returned 9,715 markers of which the caller kept 2,156, and the work
 * that scales with that count — the walk, positioning each marker, and grouping
 * them — was 44 ms of the tile's 52 ms.
 *
 * Splitting the root into two square halves removes the factor of two. The
 * string form keeps its shape: a hemisphere digit followed by geocell
 * characters over `0123456789abcdef`, one per 4x4 subdivision, and a prefix is
 * still a cell. What it is no longer is byte-identical to `geomodel.js`.
 *
 * The three SDKs hold the key as a `Long`, an `Int64` and a `number`; the
 * string is what their conformance fixtures compare, because it does not depend
 * on that choice.
 */
@InternalMapConductorApi
internal object MarkerGrid {
    /**
     * Levels of 2x2 subdivision below the two root cells.
     *
     * 18 is the largest depth that still leaves room for the position: the key
     * is `2 * 18 + 1 = 37` bits — the extra one is the hemisphere — and the
     * position below it is 24, which is 61 of the 63 a `Long` has. It is also
     * an even number of levels, so the string form packs into whole 4-bit
     * characters with no ragged tail.
     *
     * A cell at the bottom is 0.000687 degrees on a side, about 76 m at the
     * equator. The flat grid this replaced was 0.005 degrees.
     */
    const val GRID_DEPTH = 18

    /**
     * Roughly how many cells across a box a query aims to walk.
     *
     * The level is chosen so the box covers about this many cells squared,
     * which bounds the walk no matter how large the box is. That is what
     * removed the old "past 4096 cells, scan every marker instead" escape: a
     * world-sized box now asks a coarse level and reads roughly the markers it
     * would have scanned anyway, without the cliff.
     */
    private const val CELLS_ACROSS = 32.0

    /**
     * The most cells a query walks along either axis.
     *
     * Only bites on a box so flat or so narrow that its area says nothing about
     * how many cells it crosses.
     */
    private const val MAX_CELLS_PER_AXIS = 64.0

    private const val ALPHABET = "0123456789abcdef"

    /** Degrees a cell spans, on either axis. Cells are square. */
    fun cellSize(level: Int): Double = 180.0 / (1L shl level)

    /** Rows of cells from pole to pole at [level]. */
    fun rows(level: Int): Long = 1L shl level

    /**
     * Columns of cells around the globe at [level]. Twice the rows, because the
     * world is twice as wide as it is tall and the cells are square.
     */
    fun columns(level: Int): Long = 1L shl (level + 1)

    /**
     * The coarsest level whose cells are no wider than [separation], or null if
     * even the bottom level is coarser than that.
     *
     * Counted up rather than solved with a logarithm, because the three SDKs
     * have to agree on the answer and they do not have the same `log2`. Java
     * has none at all — Kotlin computes `ln(x) / ln(2)`, which is off by an ulp
     * where the true answer is a whole number, and a `ceil` above it turns that
     * ulp into a different level. Halving a double and comparing it is exact on
     * all three.
     */
    fun levelForSeparation(separation: Double): Int? {
        if (!(separation > 0.0)) return null
        var level = 0
        while (level <= GRID_DEPTH) {
            if (cellSize(level) <= separation) return level
            level++
        }
        return null
    }

    /**
     * The level a bounds query walks, chosen so the box covers about
     * [CELLS_ACROSS] squared cells.
     *
     * Counted up for the same reason as [levelForSeparation].
     *
     * The area rule alone is not enough. A box with no height — a click box
     * flattened by a degenerate projection, a bounds built from two points on
     * the same parallel — has an area of nothing, which asks for the bottom
     * level, which is a quarter of a million columns to walk.
     * [MAX_CELLS_PER_AXIS] steps back up until neither axis is absurd. On a box
     * of ordinary shape the area rule binds first and this does nothing.
     */
    fun queryLevel(
        latSpan: Double,
        lonSpan: Double,
    ): Int {
        val area = maxOf(lonSpan, 1e-12) * maxOf(latSpan, 1e-12)
        // cells at a level = area / cellSize^2, and cellSize = 180 / 2^level
        val budget = CELLS_ACROSS * CELLS_ACROSS * 180.0 * 180.0
        var level = 0
        while (level < GRID_DEPTH && area * (1L shl (2 * (level + 1))).toDouble() <= budget) {
            level++
        }
        while (level > 0 &&
            (
                lonSpan / cellSize(level) > MAX_CELLS_PER_AXIS ||
                    latSpan / cellSize(level) > MAX_CELLS_PER_AXIS
            )
        ) {
            level--
        }
        return level
    }

    /**
     * How far east the box runs, from its west edge to its east one.
     *
     * A box crossing the antimeridian has its east corner west of its west one,
     * and a padded box can run past ±180 outright. Normalising to a single
     * eastward span removes both cases: everything downstream walks east from
     * the west edge for this many degrees, and never compares two longitudes.
     */
    fun eastwardSpan(
        west: Double,
        east: Double,
    ): Double {
        if (east - west >= 360.0) return 360.0
        return ((east - west) % 360.0 + 360.0) % 360.0
    }

    /**
     * Where the column walk starts, packed with how many columns to walk.
     *
     * The end column is taken from `west + span`, not from the east corner.
     * Taking it from the corner cannot work once column indices fold: the
     * column holding 180 and the column holding -180 are the same one, so a box
     * from -180.2 to 179.8 starts and ends on the same column and the walk
     * covers one column out of the level's many. Nothing errors; the query
     * simply answers for a thin slice of the world.
     *
     * The old row-major key did not have this hazard, because it indexed on
     * unwrapped column numbers that kept growing eastward. Folding is what
     * makes a Morton key a fixed width, so the walk has to carry the span
     * itself.
     *
     * Returned as a pair rather than two calls so the two halves cannot drift.
     */
    fun columnWalk(
        west: Double,
        span: Double,
        level: Int,
    ): Pair<Long, Long> {
        val total = columns(level)
        val start = Math.floor((west + 180.0) / 360.0 * total).toLong()
        val end = Math.floor((west + span + 180.0) / 360.0 * total).toLong()
        // A full turn ends on the column it started on; walking both would
        // return that column's markers twice.
        return start to minOf(end - start + 1, total)
    }

    fun latCell(
        latitude: Double,
        level: Int,
    ): Long {
        val count = rows(level)
        val at = Math.floor((latitude + 90.0) / 180.0 * count).toLong()
        return minOf(maxOf(at, 0L), count - 1)
    }

    fun lonCell(
        longitude: Double,
        level: Int,
    ): Long {
        val count = columns(level)
        return wrap(Math.floor((longitude + 180.0) / 360.0 * count).toLong(), level)
    }

    fun wrap(
        lonCell: Long,
        level: Int,
    ): Long = Math.floorMod(lonCell, columns(level))

    fun mortonKeyFor(position: GeoPointInterface): Long =
        morton(
            latCell(position.latitude, GRID_DEPTH),
            lonCell(position.longitude, GRID_DEPTH),
            GRID_DEPTH,
        )

    /**
     * Interleaves the two indices, latitude in the odd bits, under the bit that
     * says which root cell they are in.
     *
     * Longitude carries one bit more than latitude — twice as many columns as
     * rows — and that top bit is the hemisphere. It sits above the interleaved
     * pairs rather than inside them, so dropping the low two bits still names
     * the parent cell.
     */
    fun morton(
        latCell: Long,
        lonCell: Long,
        level: Int,
    ): Long {
        val hemisphere = lonCell shr level
        val within = lonCell and ((1L shl level) - 1)
        return (hemisphere shl (2 * level)) or (spread(latCell) shl 1) or spread(within)
    }

    /** Spreads a value's bits apart, leaving a zero between each pair. */
    private fun spread(value: Long): Long {
        var x = value and 0xFFFFFFFFL
        x = (x or (x shl 16)) and 0x0000FFFF0000FFFFL
        x = (x or (x shl 8)) and 0x00FF00FF00FF00FFL
        x = (x or (x shl 4)) and 0x0F0F0F0F0F0F0F0FL
        x = (x or (x shl 2)) and 0x3333333333333333L
        x = (x or (x shl 1)) and 0x5555555555555555L
        return x
    }

    /**
     * The cell's name as a string: a hemisphere digit, then one character per
     * 4x4 subdivision over `0123456789abcdef`.
     *
     * Each character after the first is two levels of this grid, so a string of
     * [characters] names the cell at level `2 * (characters - 1)`. Not used by
     * the index itself — the key is the same information, and faster — but it is
     * the form the three SDKs' conformance fixtures compare, because it does not
     * depend on how a platform stores an integer.
     */
    fun geocell(
        latitude: Double,
        longitude: Double,
        characters: Int,
    ): String {
        val level = minOf(maxOf(characters - 1, 0) * 2, GRID_DEPTH)
        val key = morton(latCell(latitude, level), lonCell(longitude, level), level)
        val text = StringBuilder()
        text.append(key shr (2 * level))
        var at = level * 2 - 4
        while (at >= 0) {
            text.append(ALPHABET[((key shr at) and 0xF).toInt()])
            at -= 4
        }
        return text.toString()
    }
}
