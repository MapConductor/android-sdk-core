package com.mapconductor.core.polygon

import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.shapetile.ShapeTiler

/** The id of the raster layer that shows tiled polygons. */
const val POLYGON_TILE_LAYER_ID: String = "mc-polygon-tiles"

/** The raster layer state for tiled polygons at [template]. */
fun polygonTileRasterLayerState(
    template: String,
    tileSize: Int = 256,
): RasterLayerState =
    RasterLayerState(
        id = POLYGON_TILE_LAYER_ID,
        source = RasterLayerSource.UrlTemplate(template = template, tileSize = tileSize),
    )

/**
 * Decides which of a map's polygons are drawn as tiles, keeps them registered
 * with the local tile server, and tells the provider where the tiles are.
 *
 * Every provider's polygon controller uses it the same way:
 * 1. [route] with the full list on every composition. It returns the ids now
 *    in tiles; the controller keeps those in its [PolygonManager] (taps are
 *    resolved there, against the original shapes) but gives the map SDK
 *    nothing for them, and removes any native polygon it had drawn for one.
 * 2. [update] for an in-place change of a tiled polygon, or [remove] when the
 *    change means it can no longer be tiled.
 * 3. [onTemplate] is how the tiles reach the map: a URL template to show as a
 *    raster layer, or null to remove it. A provider with a RasterLayer
 *    controller hands [polygonTileRasterLayerState] to its `upsert`, and
 *    removes it with `removeById(POLYGON_TILE_LAYER_ID)`.
 *
 * The routing is [ShapeTiler]'s, shared with [com.mapconductor.core.polyline.PolylineTiler].
 *
 * Same as js-sdk-core's and ios-sdk-core's `PolygonTiler`.
 */
class PolygonTiler(
    val options: PolygonTilingOptions = PolygonTilingOptions.Default,
) {
    var onTemplate: (suspend (template: String?, tileSize: Int) -> Unit)? = null

    private val tiler =
        ShapeTiler(
            tileSet = PolygonTileSet().shapes,
            canTile = PolygonTileSet::canTile,
            vertexCount = PolygonTileSet::vertexCount,
            shouldUseTiles = { count, vertices -> options.shouldUseTiles(count, vertices) },
            idOf = { it.id },
            onTemplate = { template, tileSize -> onTemplate?.invoke(template, tileSize) },
        )

    /** Re-decides tiling for [data] and returns the ids drawn as tiles. */
    suspend fun route(data: List<PolygonState>): Set<String> = tiler.route(data)

    /** The states drawn as tiles, as of the last [route]. */
    fun tiledStates(): List<PolygonState> = tiler.tiledStates()

    fun isTiled(id: String): Boolean = tiler.isTiled(id)

    /** An in-place change of a tiled polygon; only the tiles it touches are drawn again. */
    fun update(state: PolygonState) = tiler.update(state)

    internal suspend fun reorder(state: PolygonState) = tiler.reorder(state)

    /**
     * Takes one polygon out of the tiles, for a change that left it no longer
     * [PolygonTileSet.canTile]; only the tiles it was in are drawn again. The
     * controller then hands it to the map SDK.
     */
    fun remove(id: String) = tiler.remove(id)

    /**
     * Releases the tiles when the map goes away. Unlike [clear], it does not
     * call [onTemplate]: there is no map left to remove the layer from.
     */
    fun dispose() = tiler.dispose()

    suspend fun clear() = tiler.clear()
}
