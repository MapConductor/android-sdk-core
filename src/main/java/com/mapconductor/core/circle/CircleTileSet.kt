package com.mapconductor.core.circle

import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.normalizeLng
import com.mapconductor.core.shapetile.ShapeTileRenderer
import com.mapconductor.core.shapetile.ShapeTileSet
import com.mapconductor.core.shapetile.StorageOrder
import com.mapconductor.core.tileserver.LocalTileServer
import com.mapconductor.core.tileserver.TileServerRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The circles of one map that are drawn as raster tiles, and their route on
 * the local tile server.
 *
 * Provider-independent: a provider decides which circles to tile
 * ([CircleTilingOptions.shouldUseTiles], [canTile]), hands them to
 * [setCircles], and shows the returned URL template as a raster layer.
 * In-place changes go through [update]; each one ends in a new template (the
 * version in it is what makes the map ask again), reported to
 * [onTemplateChanged]. Tiles the change did not touch are still cached by the
 * renderer and come back without being drawn again.
 *
 * The same as [com.mapconductor.core.polyline.PolylineTileSet] for circles:
 * both are [ShapeTileSet]s, with their own route, version and tile cache.
 * Same shape as js-sdk-core's and ios-sdk-core's `CircleTileSet`.
 */
class CircleTileSet(
    server: LocalTileServer = TileServerRegistry.get(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    internal val renderer: ShapeTileRenderer<TileCircle> =
        ShapeTileRenderer(tileSize = TILE_SIZE, painter = CircleTilePainter)

    internal val shapes: ShapeTileSet<CircleState, TileCircle> =
        ShapeTileSet(
            routePrefix = "circles",
            server = server,
            scope = scope,
            renderer = renderer,
            idOf = { it.id },
            // The native renderers stack an unset zIndex by the automatic one; so do the tiles.
            zIndexOf = ::effectiveZIndex,
            toTile = TileCircle::from,
            canReuse = { state, tile -> tile.matches(state) },
            createStorage = { states ->
                if (states.size <= 2000) {
                    null
                } else {
                    val context = currentCoroutineContext()
                    val root =
                        runCatching { ResourceProvider.getAppContext().cacheDir }
                            .getOrElse { File(System.getProperty("java.io.tmpdir")) }
                    // Stored by place, so that a tile reads the few blocks round it.
                    val order =
                        StorageOrder.sortedBy(states.size) {
                            val center = states[it].center
                            StorageOrder.mortonCellKey(center.latitude, normalizeLng(center.longitude))
                        }
                    CircleTileFileStorage.create(
                        File(root, "mapconductor-circle-geometry"), states.size,
                        { TileCircle.from(states[it]) }, { context.ensureActive() }, order,
                    )
                }
            },
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
     * URL template of their tiles. Draw order is the effective zIndex (an
     * unset one is the automatic one from the centre), then the order given.
     */
    suspend fun setCircles(states: List<CircleState>): String = shapes.setShapes(states)

    fun has(id: String): Boolean = shapes.has(id)

    /**
     * One circle changed in place. A change of zIndex reorders the list and
     * needs [setCircles] instead; everything else is applied here.
     */
    fun update(state: CircleState) = shapes.update(state)

    /** Takes one circle out of the tiles. Only the tiles it was in are drawn again. */
    fun remove(id: String) = shapes.remove(id)

    fun template(): String = shapes.template()

    fun dispose() = shapes.dispose()

    companion object {
        private const val TILE_SIZE = 256
        private const val MAX_LATITUDE = 85.05112878

        /**
         * Whether a circle can be drawn in a tile. One with no radius, or one
         * that reaches a pole, the edge of the Mercator world or the
         * antimeridian, is drawn by the map SDK. Geodesic or not, the rest can.
         */
        fun canTile(state: CircleState): Boolean {
            val radius = state.radiusMeters
            if (!(radius > 0.0) || !radius.isFinite()) return false
            val lat = state.center.latitude
            val lngRaw = state.center.longitude
            if (!lat.isFinite() || !lngRaw.isFinite()) return false
            val extent = circleExtent(lat, radius, state.geodesic)
            if (extent.overPole) return false
            if (extent.north >= MAX_LATITUDE || extent.south <= -MAX_LATITUDE) return false
            val lng = normalizeLng(lngRaw)
            return lng - extent.dLng > -180.0 && lng + extent.dLng < 180.0
        }
    }
}
