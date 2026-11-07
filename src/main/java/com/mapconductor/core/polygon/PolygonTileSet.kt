package com.mapconductor.core.polygon

import com.mapconductor.core.tileserver.LocalTileServer
import com.mapconductor.core.tileserver.TileServerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

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
 * Same shape as js-sdk-core's `PolygonTileSet`. The web draws in its tile
 * service worker and hands the polygons over as Encoded Polylines; here the
 * server is in the process, so the renderer reads the states directly.
 */
class PolygonTileSet(
    private val server: LocalTileServer = TileServerRegistry.get(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val routeId: String = "polygons-${UUID.randomUUID().toString().take(8)}"

    /** Called with the new template after an [update]. */
    var onTemplateChanged: (suspend (String) -> Unit)? = null

    val tileSize: Int get() = TILE_SIZE

    private val renderer = PolygonTileRenderer(TILE_SIZE)
    private val lock = Any()
    private var indexById: Map<String, Int> = emptyMap()

    @Volatile
    private var version = 0
    private var registered = false
    private val pending = LinkedHashMap<String, PolygonState>()
    private var flushJob: Job? = null

    /**
     * Registers [states] (replacing whatever was registered) and returns the
     * URL template of their tiles.
     */
    suspend fun setPolygons(states: List<PolygonState>): String {
        cancelPending()
        val (polygons, ids) =
            withContext(Dispatchers.Default) {
                // Draw order is list order: zIndex, then the order given (sortedBy is stable).
                val ordered = states.sortedBy { it.zIndex }
                val ids = HashMap<String, Int>(ordered.size * 2)
                ordered.forEachIndexed { index, state -> ids[state.id] = index }
                ordered.map(TilePolygon::from) to ids
            }
        synchronized(lock) {
            renderer.setPolygons(polygons)
            if (!registered) server.register(routeId, renderer)
            indexById = ids
            registered = true
            version++
        }
        return template()
    }

    fun has(id: String): Boolean = synchronized(lock) { indexById.containsKey(id) }

    /**
     * One polygon changed in place. A change of zIndex reorders the list and
     * needs [setPolygons] instead; everything else is applied here.
     */
    fun update(state: PolygonState) {
        synchronized(lock) {
            if (!indexById.containsKey(state.id)) return
            pending[state.id] = state
            if (flushJob == null) {
                flushJob =
                    scope.launch {
                        delay(UPDATE_COALESCE)
                        flush()
                    }
            }
        }
    }

    fun template(): String = server.urlTemplate(routeId, TILE_SIZE, version.toString())

    fun dispose() {
        cancelPending()
        synchronized(lock) {
            if (registered) server.unregister(routeId)
            registered = false
            indexById = emptyMap()
            renderer.clear()
        }
    }

    private suspend fun flush() {
        val template =
            synchronized(lock) {
                flushJob = null
                val updates =
                    pending.values.mapNotNull { state ->
                        indexById[state.id]?.let { index -> index to TilePolygon.from(state) }
                    }
                pending.clear()
                if (updates.isEmpty() || !registered) return
                renderer.update(updates)
                version++
                template()
            }
        onTemplateChanged?.invoke(template)
    }

    private fun cancelPending() {
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = null
            pending.clear()
        }
    }

    companion object {
        private const val TILE_SIZE = 256

        /** In-place changes arriving within this window are drawn as one. */
        private val UPDATE_COALESCE = 16.milliseconds

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
