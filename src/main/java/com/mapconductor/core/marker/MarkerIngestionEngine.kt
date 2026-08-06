package com.mapconductor.core.marker

import android.os.SystemClock
import android.util.Log

/**
 * Shared ingestion logic for marker controllers.
 *
 * This engine diffs incoming [MarkerState] lists against the current [MarkerManager] state,
 * drives the [MarkerOverlayRendererInterface], and updates the [MarkerManager].
 *
 * SDK-specific controllers can inject:
 * - how to decide "tiled vs native" via [shouldTile]
 * - where to keep tiled IDs via [tiledMarkerIds]
 *
 * The tile overlay itself (RasterLayer state, cache busting, etc.) remains controller-specific.
 */
object MarkerIngestionEngine {
    data class Result(
        val tiledDataChanged: Boolean,
        val hasTiledMarkers: Boolean,
    )

    suspend fun <ActualMarker : Any> ingest(
        data: List<MarkerState>,
        markerManager: MarkerManager<ActualMarker>,
        renderer: MarkerOverlayRendererInterface<ActualMarker>,
        defaultMarkerIcon: BitmapIcon,
        tilingEnabled: Boolean,
        tiledMarkerIds: MutableSet<String>,
        shouldTile: (MarkerState) -> Boolean,
    ): Result {
        val startedAt = SystemClock.elapsedRealtime()
        markerTrace("ingest start count=${data.size} tilingEnabled=$tilingEnabled")
        val previousIds =
            markerManager
                .allEntities()
                .asSequence()
                .map { it.state.id }
                .toMutableSet()

        val added = mutableListOf<MarkerOverlayRendererInterface.AddParamsInterface>()
        val updated = mutableListOf<MarkerOverlayRendererInterface.ChangeParamsInterface<ActualMarker>>()
        val removedActualMarkers = mutableListOf<MarkerEntityInterface<ActualMarker>>()
        var tiledDataChanged = false

        data.forEach { state ->
            val wantsTiled = tilingEnabled && shouldTile(state)

            if (previousIds.contains(state.id)) {
                val prevEntity = markerManager.getEntity(state.id)!!
                val wasTiled = tiledMarkerIds.contains(state.id)

                if (wantsTiled) {
                    // A marker that was already tiled and hasn't actually changed (e.g. the same
                    // full list was resent on an unrelated recompose) doesn't need to re-register
                    // with the manager or bust the tile cache — that would force every visible
                    // tile to be redrawn for a no-op update.
                    val unchanged = wasTiled && prevEntity.fingerPrint == state.fingerPrint()
                    if (!unchanged) {
                        if (!wasTiled) {
                            prevEntity.marker?.let { removedActualMarkers.add(prevEntity) }
                            tiledMarkerIds.add(state.id)
                        }
                        markerManager.updateEntity(
                            MarkerEntity(
                                marker = null,
                                state = state,
                                visible = prevEntity.visible,
                                isRendered = true,
                                tiling = true,
                            ),
                        )
                        tiledDataChanged = true
                    }
                } else {
                    if (wasTiled) {
                        tiledMarkerIds.remove(state.id)
                        tiledDataChanged = true
                    }
                    val markerIcon = state.icon?.toBitmapIcon() ?: defaultMarkerIcon
                    updated.add(
                        object : MarkerOverlayRendererInterface.ChangeParamsInterface<ActualMarker> {
                            override val current: MarkerEntityInterface<ActualMarker> =
                                MarkerEntity(
                                    state = state,
                                    marker = prevEntity.marker,
                                    visible = prevEntity.visible,
                                    isRendered = true,
                                    tiling = wasTiled,
                                )
                            override val bitmapIcon: BitmapIcon = markerIcon
                            override val prev: MarkerEntityInterface<ActualMarker> = prevEntity
                        },
                    )
                }
                previousIds.remove(state.id)
            } else {
                if (wantsTiled) {
                    tiledMarkerIds.add(state.id)
                    markerManager.registerEntity(
                        MarkerEntity(
                            marker = null,
                            state = state,
                            visible = true,
                            isRendered = true,
                            tiling = true,
                        ),
                    )
                    tiledDataChanged = true
                } else {
                    val markerIcon = state.icon?.toBitmapIcon() ?: defaultMarkerIcon
                    added.add(
                        object : MarkerOverlayRendererInterface.AddParamsInterface {
                            override val state: MarkerState = state
                            override val bitmapIcon: BitmapIcon = markerIcon
                        },
                    )
                }
            }
        }

        markerTrace(
            "diff complete input=${data.size} add=${added.size} update=${updated.size} " +
                "remove=${removedActualMarkers.size} tiled=${tiledMarkerIds.size} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
        )

        // Remove stale entities from the manager (non-suspending, fine-grained locks inside)
        previousIds.forEach { remainId ->
            markerManager.removeEntity(remainId)?.let { removedEntity ->
                if (tiledMarkerIds.remove(remainId)) {
                    tiledDataChanged = true
                } else {
                    removedEntity.marker?.let { removedActualMarkers.add(removedEntity) }
                }
            }
        }

        if (removedActualMarkers.isNotEmpty()) {
            renderer.onRemove(removedActualMarkers)
        }

        if (added.isNotEmpty()) {
            markerTrace("renderer onAdd start count=${added.size}")
            val actualMarkers = renderer.onAdd(added)
            markerTrace("renderer onAdd end count=${added.size}")
            actualMarkers.forEachIndexed { index, actualMarker ->
                actualMarker ?: return@forEachIndexed
                val state = added[index].state
                val entity =
                    MarkerEntity(
                        marker = actualMarker,
                        state = state,
                        visible = true,
                        isRendered = true,
                    )
                markerManager.registerEntity(entity)
                state.getAnimation()?.let { renderer.onAnimate(entity) }
            }
        }

        if (updated.isNotEmpty()) {
            markerTrace("renderer onChange start count=${updated.size}")
            val actualMarkers = renderer.onChange(updated)
            markerTrace("renderer onChange end count=${updated.size}")
            actualMarkers.forEachIndexed { index, actualMarker ->
                val params = updated[index]
                markerManager.updateEntity(
                    MarkerEntity(
                        marker = actualMarker ?: params.prev.marker,
                        state = params.current.state,
                        visible = params.current.visible,
                        isRendered = true,
                    ),
                )

                val prevFinger = params.prev.fingerPrint
                val currentFinger = params.current.fingerPrint
                if (prevFinger.animation != currentFinger.animation) {
                    params.current.state.getAnimation()?.let {
                        markerManager.getEntity(params.current.state.id)?.let { entity -> renderer.onAnimate(entity) }
                    }
                }
            }
        }

        markerTrace("renderer postProcess start")
        renderer.onPostProcess()
        markerTrace(
            "ingest end count=${data.size} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
        return Result(tiledDataChanged = tiledDataChanged, hasTiledMarkers = tiledMarkerIds.isNotEmpty())
    }

    private fun markerTrace(message: String) {
        Log.d(
            "MCMarkerTrace",
            "[CoreSDK][Ingestion][t=${SystemClock.elapsedRealtime()}]" +
                "[thread=${Thread.currentThread().name}] $message",
        )
    }
}
