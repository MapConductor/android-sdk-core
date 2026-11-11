package com.mapconductor.core.circle

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

abstract class CircleController<ActualCircle>(
    val circleManager: CircleManagerInterface<ActualCircle>,
    open val renderer: CircleOverlayRendererInterface<ActualCircle>,
) : SlottedOverlayController<
        CircleState,
        CircleEntityInterface<ActualCircle>,
    >,
    OnCameraChangeReceiverInterface {
    override val zIndex: Int = 4
    val semaphore = Semaphore(1)
    private var currentCameraPosition: MapCameraPosition? = null

    var clickListener: OnCircleEventHandler? = null

    /**
     * Set by a provider that can show circle tiles (see [CircleTiler]).
     * Circles it tiles stay in [circleManager], for taps, with no native
     * overlay: their entity's `circle` is null and the renderer never sees
     * them. A renderer that redraws from the manager must skip those.
     */
    var tiler: CircleTiler? = null
    private var tiledIds: Set<String> = emptySet()

    fun dispatchClick(event: CircleEvent) {
        // 配送座標の wrap は CircleEvent の生成時に一元化済み。
        event.state.onClick?.invoke(event)
        clickListener?.invoke(event)
    }

    override suspend fun add(data: List<CircleState>) {
        semaphore.withPermit {
            val wasTiled = tiledIds
            val tiled = tiler?.route(data) ?: emptySet()
            val sharedTiles = circleManager.setTiledSource(tiler)
            tiledIds = tiled
            val native = if (tiled.isEmpty()) data else data.filter { it.id !in tiled }
            val modifiedEntities = mutableListOf<CircleEntityInterface<ActualCircle>>()
            // Only natively drawn circles are diffed against the renderer.
            val previous =
                circleManager
                    .nativeEntities()
                    .map { it.state.id }
                    .filterTo(mutableSetOf()) { it !in wasTiled }
            val added = mutableListOf<CircleOverlayRendererInterface.AddParamsInterface>()
            val updated = mutableListOf<CircleOverlayRendererInterface.ChangeParamsInterface<ActualCircle>>()
            val removed = mutableListOf<CircleEntityInterface<ActualCircle>>()

            native.forEach { state ->
                if (previous.contains(state.id)) {
                    // Gone only if destroy() cleared the manager meanwhile; nothing left to update.
                    val prevEntity = circleManager.getEntity(state.id) ?: return@withPermit
                    if (unchanged(state, prevEntity)) {
                        // 描画結果が不変なら renderer を呼ばず最新の state だけ採用する
                        // （react-sdk と同じ。composition ごとに全オーバーレイへ onChange を
                        //  再実行する無駄・チラつきを避ける）。
                        circleManager.registerEntity(CircleEntity(state = state, circle = prevEntity.circle))
                        previous.remove(state.id)
                        return@forEach
                    }
                    updated.add(
                        object : CircleOverlayRendererInterface.ChangeParamsInterface<ActualCircle> {
                            override val current: CircleEntityInterface<ActualCircle> =
                                CircleEntity(
                                    state = state,
                                    circle = prevEntity.circle,
                                )
                            override val prev: CircleEntityInterface<ActualCircle> = prevEntity
                        },
                    )
                    previous.remove(state.id)
                } else {
                    added.add(
                        object : CircleOverlayRendererInterface.AddParamsInterface {
                            override val state: CircleState = state
                        },
                    )
                    previous.remove(state.id)
                }
            }

            previous.forEach { remainId ->
                circleManager.removeEntity(remainId)?.let { removedEntity ->
                    removed.add(removedEntity)
                }
            }

            // Remove circle
            if (removed.isNotEmpty()) {
                renderer.onRemove(removed)
            }

            // Add new circles
            if (added.isNotEmpty()) {
                val actualCircles: List<ActualCircle?> = renderer.onAdd(added)
                actualCircles.forEachIndexed { index, circle ->
                    circle?.let {
                        val entity =
                            CircleEntity<ActualCircle>(
                                circle = circle,
                                state = added[index].state,
                            )
                        circleManager.registerEntity(entity)
                        modifiedEntities.add(entity)
                    }
                }
            }

            // Update changed circles
            if (updated.isNotEmpty()) {
                val actualCircles: List<ActualCircle?> = renderer.onChange(updated)
                actualCircles.forEachIndexed { index, circle ->
                    circle?.let {
                        val params = updated[index]
                        val entity =
                            CircleEntity<ActualCircle>(
                                state = params.current.state,
                                circle = circle,
                            )
                        circleManager.registerEntity(entity)
                    }
                }
            }

            // Tiled circles: kept for taps, nothing native. Those that left
            // the tiles and the list are forgotten; those that left the tiles
            // for native drawing were added above.
            if (wasTiled.isNotEmpty()) {
                val nativeIds = native.mapTo(HashSet()) { it.id }
                for (id in wasTiled) {
                    if (id !in tiled && id !in nativeIds) circleManager.removeEntity(id)
                }
            }
            if (tiled.isNotEmpty() && !sharedTiles) {
                val tiledStates = tiler!!.tiledStates()
                withContext(Dispatchers.Default) {
                    for (state in tiledStates) {
                        circleManager.registerEntity(CircleEntity(circle = inTiles(), state = state))
                    }
                }
            }

            renderer.onPostProcess()
        }
    }

    /** What a tiled circle's entity holds instead of a native circle. */
    private fun unchanged(state: CircleState, previous: CircleEntityInterface<ActualCircle>): Boolean =
        (previous as? CircleEntity<ActualCircle>)?.let { it.changeMask(state) == 0 }
            ?: (state.fingerPrint() == previous.fingerPrint)

    @Suppress("UNCHECKED_CAST")
    private fun inTiles(): ActualCircle = null as ActualCircle

    override suspend fun update(state: CircleState) {
        semaphore.withPermit {
            val prevEntity = circleManager.getEntity(state.id) ?: return
            if (state.id in tiledIds) {
                if (CircleTileSet.canTile(state)) {
                    circleManager.registerEntity(CircleEntity(circle = prevEntity.circle, state = state))
                    tiler?.update(state)
                    return
                }
                // No longer drawable in a tile (no radius, or over a pole or the
                // antimeridian): the map SDK draws it from now on. Still on the
                // map, so this is not an add. Whether the rest stay in tiles is
                // left to the next add(), which decides tiling for the whole list.
                tiledIds = tiledIds - state.id
                tiler?.remove(state.id)
                val circles =
                    renderer.onAdd(
                        listOf(
                            object : CircleOverlayRendererInterface.AddParamsInterface {
                                override val state: CircleState = state
                            },
                        ),
                    )
                val circle = circles.firstOrNull()
                if (circle != null) {
                    circleManager.registerEntity(CircleEntity(circle = circle, state = state))
                } else {
                    circleManager.removeEntity(state.id)
                }
                renderer.onPostProcess()
                return
            }
            if (unchanged(state, prevEntity)) {
                return
            }

            val circle = prevEntity.circle
            val entity =
                CircleEntity(
                    circle = circle,
                    state = state,
                )
            val circleParams =
                object : CircleOverlayRendererInterface.ChangeParamsInterface<ActualCircle> {
                    override val current: CircleEntityInterface<ActualCircle> = entity
                    override val prev: CircleEntityInterface<ActualCircle> = prevEntity
                }
            val circles = renderer.onChange(listOf(circleParams))

            circles[0]?.let {
                val entity =
                    CircleEntity<ActualCircle>(
                        circle = it,
                        state = state,
                    )
                circleManager.registerEntity(entity)
            }
            renderer.onPostProcess()
        }
    }

    override suspend fun clear() {
        semaphore.withPermit {
            val tiled = tiledIds
            tiledIds = emptySet()
            tiler?.clear()
            val entities: List<CircleEntityInterface<ActualCircle>> =
                circleManager.nativeEntities().filter { it.state.id !in tiled }
            renderer.onRemove(entities)
            // manager を空にしてから onPostProcess を呼ぶ。GL 系の描画担当は remove では何もせず、
            // onPostProcess で manager の全部を描き直すので、逆の順だと clear() 後も地図に残る。
            circleManager.clear()
            renderer.onPostProcess()
        }
    }

    /**
     * The circle a tap at [position] is on: within its radius, or within half
     * its stroke past the edge at the camera's zoom. The same whether the
     * circle is drawn natively or in tiles.
     */
    override fun find(position: GeoPointInterface): CircleEntityInterface<ActualCircle>? =
        circleManager.find(position, currentCameraPosition)

    override suspend fun onCameraChanged(mapCameraPosition: MapCameraPosition) {
        currentCameraPosition = mapCameraPosition
    }

    override fun destroy() {
        // The tile server outlives the map; without this its route keeps every
        // tiled circle (and the renderer's copies of them) alive.
        tiler?.dispose()
        // A destroyed controller can still be referenced (by its map controller,
        // or a state that has not let go yet); without this it keeps every entity.
        circleManager.clear()
    }

    override fun has(id: String): Boolean = circleManager.hasEntity(id)

    override val kind: OverlayKind = OverlayKind.Circle

    override fun resolveTap(position: GeoPointInterface): OverlayHit? =
        find(position)?.let { entity ->
            OverlayHit(OverlayKind.Circle, position) {
                dispatchClick(CircleEvent(entity.state, position))
            }
        }

    @Suppress("UNCHECKED_CAST")
    override fun setClickListenerAny(listener: Any?) {
        clickListener = listener as? com.mapconductor.core.circle.OnCircleEventHandler
    }
}
