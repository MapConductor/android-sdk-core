package com.mapconductor.core

import com.mapconductor.core.circle.CircleController
import com.mapconductor.core.circle.CircleEntityInterface
import com.mapconductor.core.circle.CircleManager
import com.mapconductor.core.circle.CircleOverlayRendererInterface
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageController
import com.mapconductor.core.groundimage.GroundImageEntityInterface
import com.mapconductor.core.groundimage.GroundImageManager
import com.mapconductor.core.groundimage.GroundImageOverlayRendererInterface
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.polygon.PolygonController
import com.mapconductor.core.polygon.PolygonEntityInterface
import com.mapconductor.core.polygon.PolygonManager
import com.mapconductor.core.polygon.PolygonOverlayRendererInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.PolylineController
import com.mapconductor.core.polyline.PolylineEntityInterface
import com.mapconductor.core.polyline.PolylineManager
import com.mapconductor.core.polyline.PolylineOverlayRendererInterface
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.raster.RasterLayerController
import com.mapconductor.core.raster.RasterLayerEntityInterface
import com.mapconductor.core.raster.RasterLayerManager
import com.mapconductor.core.raster.RasterLayerOverlayRendererInterface
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * clear() の後、地図に何も残らないこと。
 *
 * MapLibre / Mapbox / Mappls / OMM などの GL 系の描画担当は、remove では何もせず、
 * onPostProcess で manager の全部を描き直す。ここの偽レンダラも同じ作りにしてあり、
 * `drawn` は最後の onPostProcess で描いたもの（＝地図に見えているもの）を表す。
 *
 * clear() が manager を空にする前に onPostProcess を呼ぶと、全部を描き直して残る。
 * 呼ばなければ、前に描いたものが残る。
 */
class OverlayClearOrderTest {
    private val a = GeoPoint(35.0, 139.0)
    private val b = GeoPoint(35.1, 139.1)
    private val c = GeoPoint(35.0, 139.1)

    /** GL 系の描画担当の作り: onPostProcess で [all] の全部を描き直す。 */
    private class Drawn(
        private val all: () -> List<String>,
    ) {
        var ids: List<String> = emptyList()
            private set

        fun redraw() {
            ids = all().sorted()
        }
    }

    // ── Polygon ─────────────────────────────────────────────────────────

    private class PolygonGl(
        manager: PolygonManager<String>,
    ) : PolygonOverlayRendererInterface<String> {
        val drawn = Drawn { manager.allEntities().map { it.state.id } }

        override suspend fun onAdd(data: List<PolygonOverlayRendererInterface.AddParamsInterface>) = data.map { it.state.id }

        override suspend fun onChange(data: List<PolygonOverlayRendererInterface.ChangeParamsInterface<String>>) =
            data.map { it.current.state.id }

        override suspend fun onRemove(data: List<PolygonEntityInterface<String>>) = Unit

        override suspend fun onPostProcess() = drawn.redraw()
    }

    @Test
    fun `polygon の clear 後は何も描かれていない`() =
        runTest {
            val manager = PolygonManager<String>()
            val renderer = PolygonGl(manager)
            val controller = object : PolygonController<String>(manager, renderer) {}
            controller.add(listOf(PolygonState(points = listOf(a, b, c), id = "p1"), PolygonState(points = listOf(a, b, c), id = "p2")))
            assertEquals(listOf("p1", "p2"), renderer.drawn.ids)

            controller.clear()

            assertEquals(emptyList<String>(), renderer.drawn.ids)
        }

    // ── Polyline ────────────────────────────────────────────────────────

    private class PolylineGl(
        manager: PolylineManager<String>,
    ) : PolylineOverlayRendererInterface<String> {
        val drawn = Drawn { manager.allEntities().map { it.state.id } }

        override suspend fun onAdd(data: List<PolylineOverlayRendererInterface.AddParamsInterface>) = data.map { it.state.id }

        override suspend fun onChange(data: List<PolylineOverlayRendererInterface.ChangeParamsInterface<String>>) =
            data.map { it.current.state.id }

        override suspend fun onRemove(data: List<PolylineEntityInterface<String>>) = Unit

        override suspend fun onPostProcess() = drawn.redraw()
    }

    @Test
    fun `polyline の clear 後は何も描かれていない`() =
        runTest {
            val manager = PolylineManager<String>()
            val renderer = PolylineGl(manager)
            val controller = object : PolylineController<String>(manager, renderer) {}
            controller.add(listOf(PolylineState(points = listOf(a, b), id = "l1"), PolylineState(points = listOf(b, c), id = "l2")))
            assertEquals(listOf("l1", "l2"), renderer.drawn.ids)

            controller.clear()

            assertEquals(emptyList<String>(), renderer.drawn.ids)
        }

    // ── Circle ──────────────────────────────────────────────────────────

    private class CircleGl(
        manager: CircleManager<String>,
    ) : CircleOverlayRendererInterface<String> {
        val drawn = Drawn { manager.allEntities().map { it.state.id } }

        override suspend fun onAdd(data: List<CircleOverlayRendererInterface.AddParamsInterface>) = data.map { it.state.id }

        override suspend fun onChange(data: List<CircleOverlayRendererInterface.ChangeParamsInterface<String>>) =
            data.map { it.current.state.id }

        override suspend fun onRemove(data: List<CircleEntityInterface<String>>) = Unit

        override suspend fun onPostProcess() = drawn.redraw()
    }

    @Test
    fun `circle の clear 後は何も描かれていない`() =
        runTest {
            val manager = CircleManager<String>()
            val renderer = CircleGl(manager)
            val controller = object : CircleController<String>(manager, renderer) {}
            controller.add(
                listOf(CircleState(center = a, radiusMeters = 100.0, id = "c1"), CircleState(center = b, radiusMeters = 50.0, id = "c2")),
            )
            assertEquals(listOf("c1", "c2"), renderer.drawn.ids)

            controller.clear()

            assertEquals(emptyList<String>(), renderer.drawn.ids)
        }

    // ── GroundImage ─────────────────────────────────────────────────────

    private class GroundImageGl(
        manager: GroundImageManager<String>,
    ) : GroundImageOverlayRendererInterface<String> {
        val drawn = Drawn { manager.allEntities().map { it.state.id } }

        override suspend fun onAdd(data: List<GroundImageOverlayRendererInterface.AddParamsInterface>) = data.map { it.state.id }

        override suspend fun onChange(data: List<GroundImageOverlayRendererInterface.ChangeParamsInterface<String>>) =
            data.map { it.current.state.id }

        override suspend fun onRemove(data: List<GroundImageEntityInterface<String>>) = Unit

        override suspend fun onPostProcess() = drawn.redraw()
    }

    /** GroundImageState は Drawable を要求するが、ここでは中身を使わない。 */
    private class FakeDrawable : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) = Unit

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    @Test
    fun `groundImage の clear 後は何も描かれていない`() =
        runTest {
            val manager = GroundImageManager<String>()
            val renderer = GroundImageGl(manager)
            val controller = object : GroundImageController<String>(manager, renderer) {}
            val bounds =
                GeoRectBounds().apply {
                    extend(a)
                    extend(b)
                }
            controller.add(
                listOf(
                    GroundImageState(bounds = bounds, image = FakeDrawable(), id = "g1"),
                    GroundImageState(bounds = bounds, image = FakeDrawable(), id = "g2"),
                ),
            )
            assertEquals(listOf("g1", "g2"), renderer.drawn.ids)

            controller.clear()

            assertEquals(emptyList<String>(), renderer.drawn.ids)
        }

    // ── RasterLayer ─────────────────────────────────────────────────────

    private class RasterGl(
        manager: RasterLayerManager<String>,
        override val coroutine: CoroutineScope,
    ) : RasterLayerOverlayRendererInterface<String> {
        val drawn = Drawn { manager.allEntities().map { it.state.id } }

        override suspend fun onAdd(data: List<RasterLayerOverlayRendererInterface.AddParamsInterface>) = data.map { it.state.id }

        override suspend fun onChange(data: List<RasterLayerOverlayRendererInterface.ChangeParamsInterface<String>>) =
            data.map { it.current.state.id }

        override suspend fun onRemove(data: List<RasterLayerEntityInterface<String>>) = Unit

        override suspend fun onPostProcess() = drawn.redraw()
    }

    @Test
    fun `rasterLayer の clear 後は何も描かれていない`() =
        runTest {
            val manager = RasterLayerManager<String>()
            val renderer = RasterGl(manager, CoroutineScope(StandardTestDispatcher(testScheduler)))
            val controller = object : RasterLayerController<String>(manager, renderer) {}
            controller.add(
                listOf(
                    RasterLayerState(source = RasterLayerSource.UrlTemplate("https://example.com/a/{z}/{x}/{y}.png"), id = "r1"),
                    RasterLayerState(source = RasterLayerSource.UrlTemplate("https://example.com/b/{z}/{x}/{y}.png"), id = "r2"),
                ),
            )
            assertEquals(listOf("r1", "r2"), renderer.drawn.ids)

            controller.clear()

            assertEquals(emptyList<String>(), renderer.drawn.ids)
        }
}
