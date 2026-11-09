package com.mapconductor.core.polyline

import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.shapetile.ShapeTiler

/** The id of the raster layer that shows tiled polylines. */
const val POLYLINE_TILE_LAYER_ID: String = "mc-polyline-tiles"

/** The raster layer state for tiled polylines at [template]. */
fun polylineTileRasterLayerState(
    template: String,
    tileSize: Int = 256,
): RasterLayerState =
    RasterLayerState(
        id = POLYLINE_TILE_LAYER_ID,
        source = RasterLayerSource.UrlTemplate(template = template, tileSize = tileSize),
    )

/**
 * Decides which of a map's polylines are drawn as tiles, keeps them
 * registered with the local tile server, and tells the provider where the
 * tiles are. Used exactly as [com.mapconductor.core.polygon.PolygonTiler]:
 *
 * 1. [route] with the full list on every composition. It returns the ids now
 *    in tiles; the controller keeps those in its [PolylineManager] (taps are
 *    resolved there, against the original lines) but gives the map SDK
 *    nothing for them, and removes any native polyline it had drawn for one.
 * 2. [update] for an in-place change of a tiled polyline, or [remove] when the
 *    change means it can no longer be tiled (it became geodesic, say).
 * 3. [onTemplate] is how the tiles reach the map: a URL template to show as a
 *    raster layer, or null to remove it. A provider with a RasterLayer
 *    controller hands [polylineTileRasterLayerState] to its `upsert`, and
 *    removes it with `removeById(POLYLINE_TILE_LAYER_ID)`. The layer belongs
 *    to the polyline band: over circles, under native polylines and markers.
 *
 * [options] is read at every [route]: setting it decides again the next time
 * the polylines are handed over.
 *
 * Same as js-sdk-core's and ios-sdk-core's `PolylineTiler`.
 */
class PolylineTiler(
    options: PolylineTilingOptions = PolylineTilingOptions.Default,
) {
    @Volatile
    var options: PolylineTilingOptions = options

    var onTemplate: (suspend (template: String?, tileSize: Int) -> Unit)? = null

    private val tileSet = PolylineTileSet()

    private val tiler =
        ShapeTiler(
            tileSet = tileSet.shapes,
            canTile = PolylineTileSet::canTile,
            vertexCount = PolylineTileSet::vertexCount,
            shouldUseTiles = { count, vertices -> this.options.shouldUseTiles(count, vertices) },
            idOf = { it.id },
            onTemplate = { template, tileSize -> onTemplate?.invoke(template, tileSize) },
        )

    /** Re-decides tiling for [data] and returns the ids drawn as tiles. */
    suspend fun route(data: List<PolylineState>): Set<String> = tiler.route(data)

    /** The states drawn as tiles, as of the last [route]. */
    fun tiledStates(): List<PolylineState> = tiler.tiledStates()

    fun isTiled(id: String): Boolean = tiler.isTiled(id)

    /** An in-place change of a tiled polyline; only the tiles it touches are drawn again. */
    fun update(state: PolylineState) = tiler.update(state)

    /**
     * Takes one polyline out of the tiles, for a change that left it no longer
     * [PolylineTileSet.canTile]; only the tiles it was in are drawn again. The
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
    internal val tiles: PolylineTileSet get() = tileSet
}
