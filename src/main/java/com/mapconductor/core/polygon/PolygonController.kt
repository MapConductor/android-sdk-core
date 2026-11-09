package com.mapconductor.core.polygon

import com.mapconductor.core.controller.OverlayHit
import com.mapconductor.core.controller.OverlayKind
import com.mapconductor.core.controller.SlottedOverlayController
import com.mapconductor.core.features.GeoPointInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

abstract class PolygonController<ActualPolygon>(
    val polygonManager: PolygonManagerInterface<ActualPolygon>,
    open val renderer: PolygonOverlayRendererInterface<ActualPolygon>,
) : SlottedOverlayController<
        PolygonState,
        PolygonEntityInterface<ActualPolygon>,
    > {
    override val zIndex: Int = 3
    val semaphore = Semaphore(1)

    var clickListener: OnPolygonEventHandler? = null

    /**
     * Set by a provider that can show polygon tiles (see [PolygonTiler]).
     * Polygons it tiles stay in [polygonManager], for taps, with no native
     * overlay: their entity's `polygon` is null and the renderer never sees
     * them.
     */
    var tiler: PolygonTiler? = null
    private var tiledIds: Set<String> = emptySet()

    fun dispatchClick(event: PolygonEvent) {
        // 配送座標の wrap は PolygonEvent の生成時に一元化済み。
        event.state.onClick?.invoke(event)
        clickListener?.invoke(event)
    }

    override suspend fun add(data: List<PolygonState>) {
        semaphore.withPermit {
            val wasTiled = tiledIds
            val tiled = tiler?.route(data) ?: emptySet()
            tiledIds = tiled
            val native = if (tiled.isEmpty()) data else data.filter { it.id !in tiled }
            val modifiedEntities = mutableListOf<PolygonEntityInterface<ActualPolygon>>()
            // Only natively drawn polygons are diffed against the renderer.
            val previous =
                polygonManager
                    .allEntities()
                    .map { it.state.id }
                    .filterTo(mutableSetOf()) { it !in wasTiled }
            val added = mutableListOf<PolygonOverlayRendererInterface.AddParamsInterface>()
            val updated = mutableListOf<PolygonOverlayRendererInterface.ChangeParamsInterface<ActualPolygon>>()
            val removed = mutableListOf<PolygonEntityInterface<ActualPolygon>>()

            native.forEach { state ->
                if (previous.contains(state.id)) {
                    // Gone only if destroy() cleared the manager meanwhile; nothing left to update.
                    val prevEntity = polygonManager.getEntity(state.id) ?: return@withPermit
                    if (state.fingerPrint() == prevEntity.fingerPrint) {
                        // 描画結果が不変なら renderer を呼ばず最新の state だけ採用する（react-sdk と同じ）。
                        polygonManager.registerEntity(PolygonEntity(state = state, polygon = prevEntity.polygon))
                        previous.remove(state.id)
                        return@forEach
                    }
                    updated.add(
                        object : PolygonOverlayRendererInterface.ChangeParamsInterface<ActualPolygon> {
                            override val current: PolygonEntityInterface<ActualPolygon> =
                                PolygonEntity(
                                    state = state,
                                    polygon = prevEntity.polygon,
                                )
                            override val prev: PolygonEntityInterface<ActualPolygon> = prevEntity
                        },
                    )
                    previous.remove(state.id)
                } else {
                    added.add(
                        object : PolygonOverlayRendererInterface.AddParamsInterface {
                            override val state: PolygonState = state
                        },
                    )
                    previous.remove(state.id)
                }
            }

            previous.forEach { remainId ->
                polygonManager.getEntity(remainId)?.let { removedEntity ->
                    removed.add(removedEntity)
                }
            }

            // Remove from the map first, then forget the entities. If this sync is
            // cancelled mid-removal (e.g. the collector flow emits again), the manager
            // still tracks the leftovers so the next sync retries the removal instead
            // of orphaning graphics on the map.
            if (removed.isNotEmpty()) {
                renderer.onRemove(removed)
                removed.forEach { polygonManager.removeEntity(it.state.id) }
            }

            // Add new polygons
            if (added.isNotEmpty()) {
                val actualPolygons: List<ActualPolygon?> = renderer.onAdd(added)
                actualPolygons.forEachIndexed { index, polygon ->
                    polygon?.let {
                        val entity =
                            PolygonEntity<ActualPolygon>(
                                polygon = polygon,
                                state = added[index].state,
                            )
                        polygonManager.registerEntity(entity)
                        modifiedEntities.add(entity)
                    }
                }
            }

            // Update changed polygons
            if (updated.isNotEmpty()) {
                val actualPolygons: List<ActualPolygon?> = renderer.onChange(updated)
                actualPolygons.forEachIndexed { index, polygon ->
                    polygon?.let {
                        val params = updated[index]
                        val entity =
                            PolygonEntity<ActualPolygon>(
                                state = params.current.state,
                                polygon = polygon,
                            )
                        polygonManager.registerEntity(entity)
                    }
                }
            }

            // Tiled polygons: kept for taps, nothing native. Those that left
            // the tiles and the list are forgotten; those that left the tiles
            // for native drawing were added above.
            if (wasTiled.isNotEmpty()) {
                val nativeIds = native.mapTo(HashSet()) { it.id }
                for (id in wasTiled) {
                    if (id !in tiled && id !in nativeIds) polygonManager.removeEntity(id)
                }
            }
            if (tiled.isNotEmpty()) {
                val tiledStates = tiler!!.tiledStates()
                withContext(Dispatchers.Default) {
                    for (state in tiledStates) {
                        polygonManager.registerEntity(PolygonEntity(polygon = inTiles(), state = state))
                    }
                }
            }

            renderer.onPostProcess()
        }
    }

    /** What a tiled polygon's entity holds instead of a native polygon. */
    @Suppress("UNCHECKED_CAST")
    private fun inTiles(): ActualPolygon = null as ActualPolygon

    override suspend fun update(state: PolygonState) {
        semaphore.withPermit {
            val prevEntity = polygonManager.getEntity(state.id) ?: return
            if (state.id in tiledIds) {
                if (PolygonTileSet.canTile(state)) {
                    polygonManager.registerEntity(PolygonEntity(polygon = prevEntity.polygon, state = state))
                    tiler?.update(state)
                    return
                }
                // No longer drawable in a tile (geodesic, half the world wide, or
                // under 3 points): the map SDK draws it from now on. Still on the
                // map, so this is not an add. Whether the rest stay in tiles is
                // left to the next add(), which decides tiling for the whole list.
                tiledIds = tiledIds - state.id
                tiler?.remove(state.id)
                val polygons =
                    renderer.onAdd(
                        listOf(
                            object : PolygonOverlayRendererInterface.AddParamsInterface {
                                override val state: PolygonState = state
                            },
                        ),
                    )
                val polygon = polygons.firstOrNull()
                if (polygon != null) {
                    polygonManager.registerEntity(PolygonEntity(polygon = polygon, state = state))
                } else {
                    polygonManager.removeEntity(state.id)
                }
                renderer.onPostProcess()
                return
            }
            val currentFinger = state.fingerPrint()
            val prevFinger = prevEntity.fingerPrint
            if (currentFinger == prevFinger) {
                return
            }

            val polygon = prevEntity.polygon
            val entity =
                PolygonEntity(
                    polygon = polygon,
                    state = state,
                )
            val polygonParams =
                object : PolygonOverlayRendererInterface.ChangeParamsInterface<ActualPolygon> {
                    override val current: PolygonEntityInterface<ActualPolygon> = entity
                    override val prev: PolygonEntityInterface<ActualPolygon> = prevEntity
                }
            val polygons = renderer.onChange(listOf(polygonParams))

            polygons[0]?.let {
                val entity =
                    PolygonEntity<ActualPolygon>(
                        polygon = it,
                        state = state,
                    )
                polygonManager.registerEntity(entity)
            }
            // ios-sdk / react-sdk と同じく update() でも onPostProcess を呼んで単一更新をコミットする。
            renderer.onPostProcess()
        }
    }

    override suspend fun clear() {
        semaphore.withPermit {
            val tiled = tiledIds
            tiledIds = emptySet()
            tiler?.clear()
            val entities: List<PolygonEntityInterface<ActualPolygon>> =
                polygonManager.allEntities().filter { it.state.id !in tiled }
            renderer.onRemove(entities)
            // manager を空にしてから onPostProcess を呼ぶ。GL 系の描画担当は remove では何もせず、
            // onPostProcess で manager の全部を描き直すので、逆の順だと clear() 後も地図に残る。
            polygonManager.clear()
            renderer.onPostProcess()
        }
    }

    override fun find(position: GeoPointInterface): PolygonEntityInterface<ActualPolygon>? =
        polygonManager
            .find(position)

    override fun destroy() {
        // The tile server outlives the map; without this its route keeps every
        // tiled polygon (and the renderer's copies of them) alive.
        tiler?.dispose()
        // A destroyed controller can still be referenced (by its map controller,
        // or a state that has not let go yet); without this it keeps every entity.
        polygonManager.clear()
    }

    override fun has(id: String): Boolean = polygonManager.hasEntity(id)

    override val kind: OverlayKind = OverlayKind.Polygon

    override fun resolveTap(position: GeoPointInterface): OverlayHit? =
        find(position)?.let { entity ->
            OverlayHit(OverlayKind.Polygon, position) {
                dispatchClick(PolygonEvent(entity.state, position))
            }
        }

    @Suppress("UNCHECKED_CAST")
    override fun setClickListenerAny(listener: Any?) {
        clickListener = listener as? com.mapconductor.core.polygon.OnPolygonEventHandler
    }
}
