package com.mapconductor.core.polygon

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL

@RunWith(AndroidJUnit4::class)
/** Regression coverage for draw order and interrupted native-to-tile transitions. */
class PolygonAuditDeviceTest {
    private fun state(id: String, color: Color = Color.Red, z: Int = 0) = PolygonState(
        id = id, points = listOf(GeoPoint(-20.0,-20.0), GeoPoint(-20.0,20.0), GeoPoint(20.0,20.0), GeoPoint(20.0,-20.0)),
        fillColor = color, zIndex = z,
    )
    private fun pixel(template: String): Int {
        val bytes = URL(template.replace("{z}","0").replace("{x}","0").replace("{y}","0")).readBytes()
        val b = BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        return b.getPixel(b.width/2,b.height/2).also { b.recycle() }
    }
    @Test fun changingZIndexMustReorderTiles() = runBlocking {
        val controller = object : PolygonController<String>(PolygonManager(),Renderer()) {}
        var template = ""
        controller.tiler = PolygonTiler(PolygonTilingOptions(minPolygonCount=2)).apply {
            onTemplate = { value, _ -> template = value.orEmpty() }
        }
        try {
            val red = state("red",Color.Red,0)
            val blue = state("blue",Color.Blue,1)
            controller.add(listOf(red,blue))
            assertEquals(android.graphics.Color.BLUE,pixel(template))
            red.zIndex = 2
            controller.update(red)
            assertEquals("red should now cover blue",android.graphics.Color.RED,pixel(template))
        } finally { controller.destroy() }
    }
    private class Renderer : PolygonOverlayRendererInterface<String> {
        val onMap = mutableSetOf<String>()
        var cancelRemoval = false
        override suspend fun onAdd(data: List<PolygonOverlayRendererInterface.AddParamsInterface>) = data.map { it.state.id.also(onMap::add) }
        override suspend fun onChange(data: List<PolygonOverlayRendererInterface.ChangeParamsInterface<String>>) = data.map { it.current.state.id }
        override suspend fun onRemove(data: List<PolygonEntityInterface<String>>) {
            if (cancelRemoval) { cancelRemoval = false; throw CancellationException("simulated resync cancellation") }
            data.forEach { onMap.remove(it.state.id) }
        }
        override suspend fun onPostProcess() {}
    }
    @Test fun cancelledTransitionMustRetryNativeRemoval() = runBlocking {
        val renderer = Renderer()
        val controller = object : PolygonController<String>(PolygonManager(),renderer) {}
        controller.tiler = PolygonTiler(PolygonTilingOptions(minPolygonCount=2))
        try {
            val first = state("first")
            controller.add(listOf(first))
            renderer.cancelRemoval = true
            val many = listOf(first,state("second"))
            try { controller.add(many); fail("expected cancellation") } catch (_: CancellationException) {}
            controller.add(many)
            assertTrue("native polygon must be removed after retry: ${renderer.onMap}",renderer.onMap.isEmpty())
        } finally { controller.destroy() }
    }
}
