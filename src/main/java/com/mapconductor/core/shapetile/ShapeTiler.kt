package com.mapconductor.core.shapetile

import com.mapconductor.core.InternalMapConductorApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decides which of a map's shapes are drawn as tiles and keeps them registered
 * in [tileSet]: the part `PolygonTiler` and `PolylineTiler` share. See
 * `PolygonTiler` for how a provider's controller uses it.
 *
 * [shouldUseTiles] is read at every [route], so a tiler whose options change
 * decides again on the next one.
 */
@InternalMapConductorApi
internal class ShapeTiler<S>(
    private val tileSet: ShapeTileSet<S, *>,
    private val canTile: (S) -> Boolean,
    private val vertexCount: (List<S>) -> Int,
    private val shouldUseTiles: (count: Int, vertices: Int) -> Boolean,
    private val idOf: (S) -> String,
    private val onTemplate: suspend (template: String?, tileSize: Int) -> Unit,
) {
    @Volatile
    private var tiled: Set<String> = emptySet()

    @Volatile
    private var states: List<S> = emptyList()

    init {
        tileSet.onTemplateChanged = { template -> onTemplate(template, tileSet.tileSize) }
    }

    /** Re-decides tiling for [data] and returns the ids drawn as tiles. */
    suspend fun route(data: List<S>): Set<String> {
        // A city of shapes is a second of work; none of it belongs on the map's thread.
        val (tileable, useTiles) =
            withContext(Dispatchers.Default) {
                val tileable = data.filter(canTile)
                tileable to shouldUseTiles(tileable.size, vertexCount(tileable))
            }
        val template = if (useTiles) tileSet.setShapes(tileable) else null
        if (template == null) {
            tileSet.dispose()
            tiled = emptySet()
            states = emptyList()
        } else {
            // Share the tile set's ID index instead of retaining a second hash set.
            tiled = tileSet.ids()
            states = tileable
        }
        onTemplate(template, tileSet.tileSize)
        return tiled
    }

    fun tiledStates(): List<S> = states

    fun isTiled(id: String): Boolean = tiled.contains(id)

    fun update(state: S) {
        tileSet.update(state)
    }

    /** Rebuild draw order without re-evaluating the native/tile threshold. */
    suspend fun reorder(state: S) {
        val updated = ArrayList<S>()
        tileSet.visitSources { updated.add(if (idOf(it) == idOf(state)) state else it) }
        val template = tileSet.setShapes(updated)
        states = updated
        tiled = tileSet.ids()
        onTemplate(template, tileSet.tileSize)
    }

    fun remove(id: String) {
        if (id !in tiled) return
        tiled = tiled - id
        states = states.filter { idOf(it) != id }
        tileSet.remove(id)
    }

    fun dispose() {
        tileSet.dispose()
        tiled = emptySet()
        states = emptyList()
    }

    suspend fun clear() {
        tileSet.dispose()
        tiled = emptySet()
        states = emptyList()
        onTemplate(null, tileSet.tileSize)
    }
}
