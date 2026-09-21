package com.mapconductor.core.marker

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.tileserver.TileRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.BitmapFactory
import android.util.Log

/**
 * Adjacent rendered tiles must agree at the seam, declutter included.
 *
 * `MarkerTileSeamTest` (JVM) checks that the index hands both tiles the same
 * candidates. This renders the actual pixels, because the seam can also be
 * broken after the query: if the declutter winner inside a boundary cell
 * depends on enumeration order, the two tiles keep different markers and a
 * circle appears cut in half against the tile edge — found on device with the
 * base layer hidden.
 *
 * The comparison looks at the two pixel columns facing each other across the
 * seam. A marker straddling it paints both; a broken seam leaves one side
 * opaque and the other transparent for roughly the icon's whole height. An
 * icon merely tangent to the seam can differ for a short run, so only long
 * runs count.
 */
@RunWith(AndroidJUnit4::class)
class MarkerTileSeamDeviceTest {
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

    @Test
    fun adjacentTilesAgreeAtEveryVerticalSeam() {
        val renderer =
            MarkerTileRenderer(
                markerManager = manager(),
                tileSize = 256,
                cacheSizeBytes = 1,
                declutterPx = 14,
            )
        var seams = 0
        var bad = 0
        // z15+ is a different path: the declutter separation drops below the
        // grid's bottom level there, the index declines the thinned query and
        // the renderer falls back to the full one.
        for (z in intArrayOf(12, 13, 14, 15, 16, 17)) {
            val n = 1 shl z
            val centerX = ((139.75 + 180.0) / 360.0 * n).toInt()
            val centerY = run {
                val latRad = Math.toRadians(35.69)
                ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
            }
            for (dx in -3..2) {
                for (dy in -2..2) {
                    val at = renderer.renderTile(TileRequest(x = centerX + dx, y = centerY + dy, z = z))
                    val east = renderer.renderTile(TileRequest(x = centerX + dx + 1, y = centerY + dy, z = z))
                    val south = renderer.renderTile(TileRequest(x = centerX + dx, y = centerY + dy + 1, z = z))
                    assertNotNull(at)
                    assertNotNull(east)
                    assertNotNull(south)
                    val tile = BitmapFactory.decodeByteArray(at!!, 0, at.size)
                    val eastTile = BitmapFactory.decodeByteArray(east!!, 0, east.size)
                    val southTile = BitmapFactory.decodeByteArray(south!!, 0, south.size)
                    seams += 2
                    var worst = 0
                    var run = 0
                    for (y in 0 until tile.height) {
                        val a = (tile.getPixel(tile.width - 1, y) ushr 24) > 0x20
                        val b = (eastTile.getPixel(0, y) ushr 24) > 0x20
                        if (a != b) { run += 1; if (run > worst) worst = run } else run = 0
                    }
                    if (worst > 16) {
                        bad += 1
                        Log.i(TAG, "SEAM broken(vertical) z=$z x=${centerX + dx}/${centerX + dx + 1} y=${centerY + dy} worstRun=$worst")
                    }
                    worst = 0
                    run = 0
                    for (x in 0 until tile.width) {
                        val a = (tile.getPixel(x, tile.height - 1) ushr 24) > 0x20
                        val b = (southTile.getPixel(x, 0) ushr 24) > 0x20
                        if (a != b) { run += 1; if (run > worst) worst = run } else run = 0
                    }
                    if (worst > 16) {
                        bad += 1
                        Log.i(TAG, "SEAM broken(horizontal) z=$z x=${centerX + dx} y=${centerY + dy}/${centerY + dy + 1} worstRun=$worst")
                    }
                }
            }
        }
        Log.i(TAG, "SEAM seams=$seams bad=$bad")
        assertEquals("seams with a half-drawn marker", 0, bad)
    }

    private companion object {
        private const val TAG = "MarkerTileSeamDeviceTest"
    }
}
