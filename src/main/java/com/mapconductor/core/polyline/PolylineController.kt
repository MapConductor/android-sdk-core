package com.mapconductor.core.polyline

import com.mapconductor.core.controller.OnCameraChangeReceiverInterface
import com.mapconductor.core.controller.OverlayHit
import com.mapconductor.core.controller.OverlayKind
import com.mapconductor.core.controller.SlottedOverlayController
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapCameraPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

abstract class PolylineController<ActualPolyline>(
    val polylineManager: PolylineManagerInterface<ActualPolyline>,
    open val renderer: PolylineOverlayRendererInterface<ActualPolyline>,
) : SlottedOverlayController<
        PolylineState,
        PolylineEntityInterface<ActualPolyline>,
    >,
    OnCameraChangeReceiverInterface {
    override val zIndex: Int = 5
    val semaphore = Semaphore(1)
    private var currentCameraPosition: MapCameraPosition? = null

    var clickListener: OnPolylineEventHandler? = null

    /**
     * Set by a provider that can show polyline tiles (see [PolylineTiler]).
     * Polylines it tiles stay in [polylineManager], for taps, with no native
     * overlay: their entity's `polyline` is null and the renderer never sees
     * them. A renderer that redraws from the manager must skip those.
     */
    var tiler: PolylineTiler? = null
    private var tiledIds: Set<String> = emptySet()

    fun dispatchClick(event: PolylineEvent) {
        // 配送座標の wrap は PolylineEvent の生成時に一元化済み。
        event.state.onClick?.invoke(event)
        clickListener?.invoke(event)
    }

    override suspend fun add(data: List<PolylineState>) {
        semaphore.withPermit {
            val wasTiled = tiledIds
            val tiled = tiler?.route(data) ?: emptySet()
            tiledIds = tiled
            val native = if (tiled.isEmpty()) data else data.filter { it.id !in tiled }
            val modifiedEntities = mutableListOf<PolylineEntityInterface<ActualPolyline>>()
            // Only natively drawn polylines are diffed against the renderer.
            val previous =
                polylineManager
                    .allEntities()
                    .map { it.state.id }
                    .filterTo(mutableSetOf()) { it !in wasTiled }
            val added = mutableListOf<PolylineOverlayRendererInterface.AddParamsInterface>()
            val updated = mutableListOf<PolylineOverlayRendererInterface.ChangeParamsInterface<ActualPolyline>>()
            val removed = mutableListOf<PolylineEntityInterface<ActualPolyline>>()

            native.forEach { state ->
                if (previous.contains(state.id)) {
                    // Gone only if destroy() cleared the manager meanwhile; nothing left to update.
                    val prevEntity = polylineManager.getEntity(state.id) ?: return@withPermit
                    if (state.fingerPrint() == prevEntity.fingerPrint) {
                        // 描画結果が不変なら renderer を呼ばず最新の state だけ採用する（react-sdk と同じ）。
                        polylineManager.registerEntity(PolylineEntity(state = state, polyline = prevEntity.polyline))
                        previous.remove(state.id)
                        return@forEach
                    }
                    updated.add(
                        object : PolylineOverlayRendererInterface.ChangeParamsInterface<ActualPolyline> {
                            override val current: PolylineEntityInterface<ActualPolyline> =
                                PolylineEntity(
                                    state = state,
                                    polyline = prevEntity.polyline,
                                )
                            override val prev: PolylineEntityInterface<ActualPolyline> = prevEntity
                        },
                    )
                    previous.remove(state.id)
                } else {
                    added.add(
                        object : PolylineOverlayRendererInterface.AddParamsInterface {
                            override val state: PolylineState = state
                        },
                    )
                    previous.remove(state.id)
                }
            }

            previous.forEach { remainId ->
                polylineManager.removeEntity(remainId)?.let { removedEntity ->
                    removed.add(removedEntity)
                }
            }

            // Remove polylines
            if (removed.isNotEmpty()) {
                renderer.onRemove(removed)
            }

            // Add new polylines
            if (added.isNotEmpty()) {
                val actualPolylines: List<ActualPolyline?> = renderer.onAdd(added)
                actualPolylines.forEachIndexed { index, polyline ->
                    polyline?.let {
                        val entity =
                            PolylineEntity<ActualPolyline>(
                                polyline = polyline,
                                state = added[index].state,
                            )
                        polylineManager.registerEntity(entity)
                        modifiedEntities.add(entity)
                    }
                }
            }

            // Update changed polylines
            if (updated.isNotEmpty()) {
                val actualPolylines: List<ActualPolyline?> = renderer.onChange(updated)

                actualPolylines.forEachIndexed { index, polyline ->
                    polyline?.let {
                        val params = updated[index]
                        val entity =
                            PolylineEntity<ActualPolyline>(
                                state = params.current.state,
                                polyline = polyline,
                            )
                        polylineManager.registerEntity(entity)
                    }
                }
            }

            // Tiled polylines: kept for taps, nothing native. Those that left
            // the tiles and the list are forgotten; those that left the tiles
            // for native drawing were added above.
            if (wasTiled.isNotEmpty()) {
                val nativeIds = native.mapTo(HashSet()) { it.id }
                for (id in wasTiled) {
                    if (id !in tiled && id !in nativeIds) polylineManager.removeEntity(id)
                }
            }
            if (tiled.isNotEmpty()) {
                val tiledStates = tiler!!.tiledStates()
                withContext(Dispatchers.Default) {
                    for (state in tiledStates) {
                        polylineManager.registerEntity(PolylineEntity(polyline = inTiles(), state = state))
                    }
                }
            }

            renderer.onPostProcess()
        }
    }

    /** What a tiled polyline's entity holds instead of a native polyline. */
    @Suppress("UNCHECKED_CAST")
    private fun inTiles(): ActualPolyline = null as ActualPolyline

    override suspend fun update(state: PolylineState) {
        semaphore.withPermit {
            val prevEntity = polylineManager.getEntity(state.id) ?: return
            if (state.id in tiledIds) {
                if (PolylineTileSet.canTile(state)) {
                    polylineManager.registerEntity(PolylineEntity(polyline = prevEntity.polyline, state = state))
                    tiler?.update(state)
                    return
                }
                // No longer drawable in a tile (geodesic, half the world wide, or
                // under 2 points): the map SDK draws it from now on. Still on the
                // map, so this is not an add. Whether the rest stay in tiles is
                // left to the next add(), which decides tiling for the whole list.
                tiledIds = tiledIds - state.id
                tiler?.remove(state.id)
                val polylines =
                    renderer.onAdd(
                        listOf(
                            object : PolylineOverlayRendererInterface.AddParamsInterface {
                                override val state: PolylineState = state
                            },
                        ),
                    )
                val polyline = polylines.firstOrNull()
                if (polyline != null) {
                    polylineManager.registerEntity(PolylineEntity(polyline = polyline, state = state))
                } else {
                    polylineManager.removeEntity(state.id)
                }
                renderer.onPostProcess()
                return
            }
            val currentFinger = state.fingerPrint()
            val prevFinger = prevEntity.fingerPrint
            if (currentFinger == prevFinger) {
                return
            }

            val polyline = prevEntity.polyline
            val entity =
                PolylineEntity(
                    polyline = polyline,
                    state = state,
                )
            val polylineParams =
                object : PolylineOverlayRendererInterface.ChangeParamsInterface<ActualPolyline> {
                    override val current: PolylineEntityInterface<ActualPolyline> = entity
                    override val prev: PolylineEntityInterface<ActualPolyline> = prevEntity
                }
            val polylines = renderer.onChange(listOf(polylineParams))

            polylines[0]?.let {
                val entity =
                    PolylineEntity<ActualPolyline>(
                        polyline = it,
                        state = state,
                    )
                polylineManager.registerEntity(entity)
            }
            renderer.onPostProcess()
        }
    }

    override suspend fun clear() {
        semaphore.withPermit {
            val tiled = tiledIds
            tiledIds = emptySet()
            tiler?.clear()
            val entities: List<PolylineEntityInterface<ActualPolyline>> =
                polylineManager.allEntities().filter { it.state.id !in tiled }
            renderer.onRemove(entities)
            // manager を空にしてから onPostProcess を呼ぶ。GL 系の描画担当は remove では何もせず、
            // onPostProcess で manager の全部を描き直すので、逆の順だと clear() 後も地図に残る。
            polylineManager.clear()
            renderer.onPostProcess()
        }
    }

    override fun find(position: GeoPointInterface): PolylineEntityInterface<ActualPolyline>? =
        polylineManager.find(position, currentCameraPosition)?.entity

    fun findWithClosestPoint(position: GeoPointInterface): PolylineHitResult<ActualPolyline>? =
        polylineManager.find(position, currentCameraPosition)

    override suspend fun onCameraChanged(mapCameraPosition: MapCameraPosition) {
        currentCameraPosition = mapCameraPosition
    }

    override fun destroy() {
        // The tile server outlives the map; without this its route keeps every
        // tiled polyline (and the renderer's copies of them) alive.
        tiler?.dispose()
        // A destroyed controller can still be referenced (by its map controller,
        // or a state that has not let go yet); without this it keeps every entity.
        polylineManager.clear()
    }

    override fun has(id: String): Boolean = polylineManager.hasEntity(id)

    override val kind: OverlayKind = OverlayKind.Polyline

    /** 配送座標はタップ点ではなく**線上の最近傍点**（3 プラットフォーム共通の契約）。 */
    override fun resolveTap(position: GeoPointInterface): OverlayHit? =
        findWithClosestPoint(position)?.let { hit ->
            OverlayHit(OverlayKind.Polyline, hit.closestPoint) {
                dispatchClick(PolylineEvent(hit.entity.state, hit.closestPoint))
            }
        }

    @Suppress("UNCHECKED_CAST")
    override fun setClickListenerAny(listener: Any?) {
        clickListener = listener as? com.mapconductor.core.polyline.OnPolylineEventHandler
    }
}
