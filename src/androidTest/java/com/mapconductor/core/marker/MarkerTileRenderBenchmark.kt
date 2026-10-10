package com.mapconductor.core.marker

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.tileserver.TileRequest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log

/**
 * What one marker tile costs on this device, with and without decluttering.
 *
 * 144k markers in the street-tree shape, the tiles central Tokyo actually
 * requests. Not a pass/fail gate — the point is the logged ms/tile, so a slow
 * device can be measured instead of guessed about. The declutter run is the
 * configuration the samples ask for; the run without it is what they were
 * silently getting while the controllers dropped `declutterPx` on the floor.
 */
@RunWith(AndroidJUnit4::class)
class MarkerTileRenderBenchmark {
    private fun manager(): MarkerManager<Unit> {
        val manager = MarkerManager.defaultManager<Unit>(minMarkerCount = 1)
        var seed = 12345uL

        fun next(): Double {
            seed = seed * 6364136223846793005uL + 1442695040888963407uL
            return (seed shr 11).toDouble() / (1uL shl 53).toDouble()
        }
        repeat(144_000) { at ->
            manager.registerEntity(
                MarkerEntity(
                    marker = null,
                    state =
                        MarkerState(
                            position = GeoPoint.fromLatLong(35.5 + next() * 0.4, 139.5 + next() * 0.5),
                            id = "$at",
                        ),
                    visible = true,
                    isRendered = true,
                    tiling = true,
                ),
            )
        }
        return manager
    }

    private fun renderViewport(
        renderer: MarkerTileRenderer<Unit>,
        z: Int,
        label: String,
    ) {
        // The tile column/row central Tokyo lands on at this zoom.
        val n = 1 shl z
        val centerX = ((139.75 + 180.0) / 360.0 * n).toInt()
        val centerY =
            run {
                val latRad = Math.toRadians(35.69)
                ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
            }
        var totalMs = 0L
        var tiles = 0
        for (dx in -1..1) {
            for (dy in -1..1) {
                val start = System.nanoTime()
                val bytes = renderer.renderTile(TileRequest(x = centerX + dx, y = centerY + dy, z = z))
                totalMs += (System.nanoTime() - start) / 1_000_000
                tiles += 1
                assertNotNull(bytes)
            }
        }
        Log.i(TAG, "RENDERBENCH $label z=$z tiles=$tiles total=${totalMs}ms perTile=${totalMs / tiles}ms")
    }

    @Test
    fun viewportCostWithAndWithoutDeclutter() {
        val manager = manager()
        for (declutter in intArrayOf(0, 14)) {
            val renderer =
                MarkerTileRenderer(
                    markerManager = manager,
                    tileSize = 256,
                    cacheSizeBytes = 1, // effectively no tile cache: measure the render
                    declutterPx = declutter,
                )
            for (z in intArrayOf(10, 11, 12, 13, 14)) {
                renderViewport(renderer, z, label = "declutter=$declutter")
            }
        }
    }

    private companion object {
        private const val TAG = "MarkerTileRenderBenchmark"
    }
}
