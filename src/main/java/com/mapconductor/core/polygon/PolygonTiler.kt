package com.mapconductor.core.polygon

import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * 2. [update] for an in-place change of a tiled polygon.
 * 3. [onTemplate] is how the tiles reach the map: a URL template to show as a
 *    raster layer, or null to remove it. A provider with a RasterLayer
 *    controller hands [polygonTileRasterLayerState] to its `upsert`, and
 *    removes it with `removeById(POLYGON_TILE_LAYER_ID)`.
 *
 * Same as js-sdk-core's and ios-sdk-core's `PolygonTiler`.
 */
class PolygonTiler(
    val options: PolygonTilingOptions = PolygonTilingOptions.Default,
) {
    var onTemplate: (suspend (template: String?, tileSize: Int) -> Unit)? = null

    private val tileSet = PolygonTileSet()

    @Volatile
    private var tiled: Set<String> = emptySet()

    @Volatile
    private var states: List<PolygonState> = emptyList()

    init {
        tileSet.onTemplateChanged = { template -> onTemplate?.invoke(template, tileSet.tileSize) }
    }

    /** Re-decides tiling for [data] and returns the ids drawn as tiles. */
    suspend fun route(data: List<PolygonState>): Set<String> {
        // A city of polygons is a second of work; none of it belongs on the map's thread.
        val (tileable, useTiles) =
            withContext(Dispatchers.Default) {
                val tileable = data.filter(PolygonTileSet::canTile)
                tileable to options.shouldUseTiles(tileable.size, PolygonTileSet.vertexCount(tileable))
            }
        val template = if (useTiles) tileSet.setPolygons(tileable) else null
        if (template == null) {
            tileSet.dispose()
            tiled = emptySet()
            states = emptyList()
        } else {
            tiled = withContext(Dispatchers.Default) { tileable.mapTo(HashSet(tileable.size * 2)) { it.id } }
            states = tileable
        }
        onTemplate?.invoke(template, tileSet.tileSize)
        return tiled
    }

    /** The states drawn as tiles, as of the last [route]. */
    fun tiledStates(): List<PolygonState> = states

    fun isTiled(id: String): Boolean = tiled.contains(id)

    /** An in-place change of a tiled polygon; only the tiles it touches are drawn again. */
    fun update(state: PolygonState) {
        tileSet.update(state)
    }

    suspend fun clear() {
        tileSet.dispose()
        tiled = emptySet()
        states = emptyList()
        onTemplate?.invoke(null, tileSet.tileSize)
    }
}
