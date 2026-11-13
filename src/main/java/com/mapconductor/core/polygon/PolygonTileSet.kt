package com.mapconductor.core.polygon

import com.mapconductor.core.shapetile.ShapeTileSet
import com.mapconductor.core.tileserver.LocalTileServer
import com.mapconductor.core.tileserver.TileServerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The polygons of one map that are drawn as raster tiles, and their route on
 * the local tile server.
 *
 * Provider-independent: a provider decides which polygons to tile
 * ([PolygonTilingOptions.shouldUseTiles], [canTile]), hands them to
 * [setPolygons], and shows the returned URL template as a raster layer.
 * In-place changes go through [update]; each one ends in a new template (the
 * version in it is what makes the map ask again), reported to
 * [onTemplateChanged]. Tiles the change did not touch are still cached by the
 * renderer and come back without being drawn again.
 *
 * The route, its version and the coalescing of changes are
 * [ShapeTileSet]'s, shared with [com.mapconductor.core.polyline.PolylineTileSet].
 *
 * Same shape as js-sdk-core's `PolygonTileSet`. The web draws in its tile
 * service worker and hands the polygons over as Encoded Polylines; here the
 * server is in the process, so the renderer reads the states directly.
 */
class PolygonTileSet(
    server: LocalTileServer = TileServerRegistry.get(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    internal val shapes: ShapeTileSet<PolygonState, TilePolygon> =
        ShapeTileSet(
            routePrefix = "polygons",
            server = server,
            scope = scope,
            renderer = PolygonTileRenderer(TILE_SIZE).shapes,
            idOf = { it.id },
            zIndexOf = { it.zIndex },
            toTile = TilePolygon::from,
        )

    val routeId: String get() = shapes.routeId

    /** Called with the new template after an [update]. */
    var onTemplateChanged: (suspend (String) -> Unit)?
        get() = shapes.onTemplateChanged
        set(value) {
            shapes.onTemplateChanged = value
        }

    val tileSize: Int get() = TILE_SIZE

    /**
     * Registers [states] (replacing whatever was registered) and returns the
     * URL template of their tiles.
     */
    suspend fun setPolygons(states: List<PolygonState>): String = shapes.setShapes(states)

    fun has(id: String): Boolean = shapes.has(id)

    /**
     * One polygon changed in place. A change of zIndex reorders the list and
     * needs [setPolygons] instead; everything else is applied here.
     */
    fun update(state: PolygonState) = shapes.update(state)

    /**
     * Takes one polygon out of the tiles. Its slot in the renderer's list is
     * left empty, so the others keep their indices and only the tiles it was
     * in are drawn again.
     */
    fun remove(id: String) = shapes.remove(id)

    fun template(): String = shapes.template()

    fun dispose() = shapes.dispose()

    companion object {
        private const val TILE_SIZE = 256

        /**
         * Whether a polygon can be drawn in a tile. Geodesic edges are curves
         * the tile does not trace, and a ring spanning half the world or more
         * crosses the antimeridian; the map SDK draws both.
         */
        fun canTile(state: PolygonState): Boolean {
            if (state.geodesic || state.points.size < 3) return false
            var min = Double.POSITIVE_INFINITY
            var max = Double.NEGATIVE_INFINITY
            for (p in state.points) {
                if (p.longitude < min) min = p.longitude
                if (p.longitude > max) max = p.longitude
            }
            return max - min < 180
        }

        fun vertexCount(states: List<PolygonState>): Int = vertexCountOf(states)
    }
}
