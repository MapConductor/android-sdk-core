package com.mapconductor.core.circle

import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.shapetile.ShapeTiler

/** The id of the raster layer that shows tiled circles. */
const val CIRCLE_TILE_LAYER_ID: String = "mc-circle-tiles"

/** The raster layer state for tiled circles at [template]. */
fun circleTileRasterLayerState(
    template: String,
    tileSize: Int = 256,
): RasterLayerState =
    RasterLayerState(
        id = CIRCLE_TILE_LAYER_ID,
        source = RasterLayerSource.UrlTemplate(template = template, tileSize = tileSize),
    )

/**
 * Decides which of a map's circles are drawn as tiles, keeps them registered
 * with the local tile server, and tells the provider where the tiles are.
 * Used exactly as [com.mapconductor.core.polyline.PolylineTiler]:
 *
 * 1. [route] with the full list on every composition. It returns the ids now
 *    in tiles; the controller keeps those in its [CircleManager] (taps are
 *    resolved there, against the original circles) but gives the map SDK
 *    nothing for them, and removes any native circle it had drawn for one.
 * 2. [update] for an in-place change of a tiled circle, or [remove] when the
 *    change means it can no longer be tiled (it moved onto the antimeridian,
 *    say).
 * 3. [onTemplate] is how the tiles reach the map: a URL template to show as a
 *    raster layer, or null to remove it. A provider with a RasterLayer
 *    controller hands [circleTileRasterLayerState] to its `upsert`, and
 *    removes it with `removeById(CIRCLE_TILE_LAYER_ID)`. The layer belongs to
 *    the circle band: over polygons (and their tiles), under native circles,
 *    polylines (and their tiles) and markers.
 *
 * [options] is read at every [route]: setting it decides again the next time
 * the circles are handed over.
 *
 * Same as js-sdk-core's and ios-sdk-core's `CircleTiler`.
 */
class CircleTiler(
    options: CircleTilingOptions = CircleTilingOptions.Default,
) {
    @Volatile
    var options: CircleTilingOptions = options

    var onTemplate: (suspend (template: String?, tileSize: Int) -> Unit)? = null

    private val tileSet = CircleTileSet()

    private val tiler =
        ShapeTiler(
            tileSet = tileSet.shapes,
            canTile = CircleTileSet::canTile,
            vertexCount = { 0 },
            shouldUseTiles = { count, _ -> this.options.shouldUseTiles(count) },
            idOf = { it.id },
            onTemplate = { template, tileSize -> onTemplate?.invoke(template, tileSize) },
        )

    /** Re-decides tiling for [data] and returns the ids drawn as tiles. */
    suspend fun route(data: List<CircleState>): Set<String> = tiler.route(data)

    /** The states drawn as tiles, as of the last [route]. */
    fun tiledStates(): List<CircleState> = tiler.tiledStates()

    fun isTiled(id: String): Boolean = tiler.isTiled(id)

    internal fun state(id: String): CircleState? = tileSet.shapes.source(id)
    internal fun visitStates(action: (CircleState) -> Unit) = tileSet.shapes.visitSources(action)

    /** An in-place change of a tiled circle; only the tiles it touches are drawn again. */
    fun update(state: CircleState) = tiler.update(state)

    /**
     * Takes one circle out of the tiles, for a change that left it no longer
     * [CircleTileSet.canTile]; only the tiles it was in are drawn again. The
     * controller then hands it to the map SDK.
     */
    fun remove(id: String) = tiler.remove(id)

    /**
     * Releases the tiles when the map goes away. Unlike [clear], it does not
     * call [onTemplate]: there is no map left to remove the layer from.
     */
    fun dispose() = tiler.dispose()

    suspend fun clear() = tiler.clear()

    /** The tile set, for tests that look at the pixels. */
    internal val tiles: CircleTileSet get() = tileSet
}
