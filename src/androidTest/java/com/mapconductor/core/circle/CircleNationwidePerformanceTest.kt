package com.mapconductor.core.circle

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.shapetile.ShapeGridIndex
import com.mapconductor.core.shapetile.ShapeTileRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.acos
import kotlin.math.ln
import kotlin.math.sin
import android.util.Log

class CircleNationwidePerformanceTest {
    @Test fun nationwideZoomTiles() {
        val bytes =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("antennas/us-antennas.bin")
                .use { it.readBytes() }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.position(4)
        val count = b.int
        val circles =
            List(count) {
                val lat = b.float.toDouble()
                val lon = b.float.toDouble()
                val height = b.float.toDouble()
                val id = b.int
                val radius = 6378137.0 * (acos(6378137.0 / (6378137.0 + height)) + acos(6378137.0 / 6378138.5))
                TileCircle.from(
                    CircleState(
                        center = GeoPoint(lat, lon), radiusMeters = radius, id = "$id", geodesic = true,
                        fillColor = Color.Blue.copy(alpha = 0.035f), strokeColor = Color.Blue.copy(alpha = 0.35f),
                        strokeWidth = 0.5.dp,
                    ),
                )!!
            }
        val start = System.nanoTime()
        val index = ShapeGridIndex.build(count) { circles[it].bbox }
        Log.i("CirclePerf", "indexMs=${(System.nanoTime() - start) / 1e6} large=${index.large.size}")
        val renderer = ShapeTileRenderer<TileCircle>(pixelScale = { 2f }, painter = CircleTilePainter)
        renderer.setItems(circles)
        for (z in listOf(4, 6, 8, 10, 12, 14)) {
            val n = 1 shl z
            val x = ((-98.0 + 180) / 360 * n).toInt()
            val latitude = 39.0 * Math.PI / 180
            val y = ((0.5 - ln((1 + sin(latitude)) / (1 - sin(latitude))) / (4 * Math.PI)) * n).toInt()
            val q = System.nanoTime()
            val candidates =
                index
                    .query({ circles[it].bbox }, x.toDouble() / n, y.toDouble() / n, (x + 1.0) / n, (y + 1.0) / n)
            val queryMs = (System.nanoTime() - q) / 1e6
            val t = System.nanoTime()
            val bitmap = renderer.renderBitmap(x, y, z)
            val renderMs = (System.nanoTime() - t) / 1e6
            assertTrue(candidates.isNotEmpty())
            assertTrue(bitmap != null)
            bitmap?.recycle()
            Log.i("CirclePerf", "z=$z candidates=${candidates.size} queryMs=$queryMs renderMs=$renderMs")
        }
    }
}
