package com.mapconductor.core.polyline

import com.mapconductor.core.shapetile.ShapeTileRenderer
import com.mapconductor.core.shapetile.ShapeTileSet
import com.mapconductor.core.tileserver.LocalTileServer
import com.mapconductor.core.tileserver.TileServerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The polylines of one map that are drawn as raster tiles, and their route on
 * the local tile server.
 *
 * Provider-independent: a provider decides which polylines to tile
 * ([PolylineTilingOptions.shouldUseTiles], [canTile]), hands them to
 * [setPolylines], and shows the returned URL template as a raster layer.
 * In-place changes go through [update]; each one ends in a new template (the
 * version in it is what makes the map ask again), reported to
 * [onTemplateChanged]. Tiles the change did not touch are still cached by the
 * renderer and come back without being drawn again.
 *
 * The same as [com.mapconductor.core.polygon.PolygonTileSet] for lines: both
 * are [ShapeTileSet]s, with their own route, version and tile cache. Same
 * shape as js-sdk-core's and ios-sdk-core's `PolylineTileSet`.
 */
class PolylineTileSet(
    server: LocalTileServer = TileServerRegistry.get(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    internal val renderer: ShapeTileRenderer<TilePolyline> =
        ShapeTileRenderer(tileSize = TILE_SIZE, painter = PolylineTilePainter)

    internal val shapes: ShapeTileSet<PolylineState, TilePolyline> =
        ShapeTileSet(
            routePrefix = "polylines",
            server = server,
            scope = scope,
            renderer = renderer,
            idOf = { it.id },
            zIndexOf = { it.zIndex },
            toTile = TilePolyline::from,
        )

    val routeId: String get() = shapes.routeId

    /** Called with the new template after an [update] or [remove]. */
    var onTemplateChanged: (suspend (String) -> Unit)?
        get() = shapes.onTemplateChanged
        set(value) {
            shapes.onTemplateChanged = value
        }

    val tileSize: Int get() = TILE_SIZE

    /**
     * Registers [states] (replacing whatever was registered) and returns the
     * URL template of their tiles. Draw order is zIndex, then the order given.
     */
    suspend fun setPolylines(states: List<PolylineState>): String = shapes.setShapes(states)

    fun has(id: String): Boolean = shapes.has(id)

    /**
     * One polyline changed in place. A change of zIndex reorders the list and
     * needs [setPolylines] instead; everything else is applied here.
     */
    fun update(state: PolylineState) = shapes.update(state)

    /**
     * Takes one polyline out of the tiles. Only the tiles it was in are drawn
     * again.
     */
    fun remove(id: String) = shapes.remove(id)

    fun template(): String = shapes.template()

    fun dispose() = shapes.dispose()

    companion object {
        private const val TILE_SIZE = 256

        /**
         * Whether a polyline can be drawn in a tile. Geodesic lines are curves
         * the tile does not trace, a line spanning half the world or more
         * crosses the antimeridian, and a line needs two points; the map SDK
         * draws the rest.
         */
        fun canTile(state: PolylineState): Boolean {
            if (state.geodesic || state.points.size < 2) return false
            var min = Double.POSITIVE_INFINITY
            var max = Double.NEGATIVE_INFINITY
            for (p in state.points) {
                if (p.longitude < min) min = p.longitude
                if (p.longitude > max) max = p.longitude
            }
            return max - min < 180
        }

        /** Points across [states]. */
        fun vertexCount(states: List<PolylineState>): Int = polylineVertexCountOf(states)
    }
}
