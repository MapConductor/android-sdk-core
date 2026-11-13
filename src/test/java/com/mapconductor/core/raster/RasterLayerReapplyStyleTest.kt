package com.mapconductor.core.raster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * `reapplyStyle()` runs under the controller lock.
 *
 * The renderer here only remembers what it last put on the map, and can be
 * held inside onChange()/onRemove(): the window between the renderer call and
 * the manager update that react-sdk's resync() fell into (js-sdk-core#15).
 * Android's renderers do not suspend there today, so this guards the lock, not
 * an interleaving that happens now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RasterLayerReapplyStyleTest {
    private data class Handle(
        val id: String,
        val generation: Int,
    )

    private class FakeRenderer(
        override val coroutine: CoroutineScope,
    ) : RasterLayerOverlayRendererInterface<Handle> {
        /** id -> the state last put on the map. */
        val onMap = mutableMapOf<String, RasterLayerState>()
        var hold: CompletableDeferred<Unit>? = null
        private var generation = 0

        override suspend fun onAdd(data: List<RasterLayerOverlayRendererInterface.AddParamsInterface>): List<Handle?> =
            data.map { params ->
                onMap[params.state.id] = params.state.copy()
                Handle(params.state.id, ++generation)
            }

        override suspend fun onChange(
            data: List<RasterLayerOverlayRendererInterface.ChangeParamsInterface<Handle>>,
        ): List<Handle?> {
            val handles =
                data.map { params ->
                    onMap[params.current.state.id] = params.current.state.copy()
                    params.prev.layer
                }
            hold?.await()
            return handles
        }

        override suspend fun onRemove(data: List<RasterLayerEntityInterface<Handle>>) {
            data.forEach { onMap.remove(it.state.id) }
            hold?.await()
        }

        override suspend fun onPostProcess() {}
    }

    private class TestController(
        renderer: FakeRenderer,
    ) : RasterLayerController<Handle>(RasterLayerManager(), renderer)

    private fun state(
        version: String,
        opacity: Float = 1.0f,
    ) = RasterLayerState(
        id = "tiles",
        source = RasterLayerSource.UrlTemplate(template = "https://example.com/$version/{z}/{x}/{y}.png"),
        opacity = opacity,
    )

    private fun TestScope.fixture(): Pair<TestController, FakeRenderer> {
        val renderer = FakeRenderer(CoroutineScope(StandardTestDispatcher(testScheduler)))
        return TestController(renderer) to renderer
    }

    /** Starts [change], holds it inside the renderer, then starts reapplyStyle() and lets both finish. */
    private suspend fun TestScope.reapplyDuring(
        renderer: FakeRenderer,
        controller: TestController,
        change: suspend () -> Unit,
    ) {
        val hold = CompletableDeferred<Unit>()
        renderer.hold = hold
        val changing = launch { change() }
        runCurrent()
        val reapplying = launch { controller.reapplyStyle() }
        runCurrent()
        renderer.hold = null
        hold.complete(Unit)
        advanceUntilIdle()
        changing.join()
        reapplying.join()
    }

    @Test
    fun `reapplyStyle during a source update keeps the new source`() =
        runTest {
            val (controller, renderer) = fixture()
            controller.add(listOf(state("circle")))
            val numbered = state("numbered")

            reapplyDuring(renderer, controller) { controller.update(numbered) }

            assertEquals(numbered.source, renderer.onMap["tiles"]?.source)
            assertEquals(
                numbered.source,
                controller.rasterLayerManager
                    .getEntity("tiles")
                    ?.state
                    ?.source,
            )
        }

    @Test
    fun `reapplyStyle during an opacity update keeps the new opacity`() =
        runTest {
            val (controller, renderer) = fixture()
            controller.add(listOf(state("circle", opacity = 1.0f)))

            reapplyDuring(renderer, controller) { controller.update(state("circle", opacity = 0.5f)) }

            assertEquals(0.5f, renderer.onMap["tiles"]?.opacity)
            assertEquals(
                0.5f,
                controller.rasterLayerManager
                    .getEntity("tiles")
                    ?.state
                    ?.opacity,
            )
        }

    @Test
    fun `reapplyStyle during a composition keeps the new source`() =
        runTest {
            val (controller, renderer) = fixture()
            controller.add(listOf(state("circle")))
            val numbered = state("numbered")

            reapplyDuring(renderer, controller) { controller.add(listOf(numbered)) }

            assertEquals(numbered.source, renderer.onMap["tiles"]?.source)
            assertEquals(
                numbered.source,
                controller.rasterLayerManager
                    .getEntity("tiles")
                    ?.state
                    ?.source,
            )
        }

    @Test
    fun `reapplyStyle cannot resurrect a layer removed by clear`() =
        runTest {
            val (controller, renderer) = fixture()
            controller.add(listOf(state("circle")))

            reapplyDuring(renderer, controller) { controller.clear() }

            assertNull(renderer.onMap["tiles"])
            assertNull(controller.rasterLayerManager.getEntity("tiles"))
        }

    @Test
    fun `reapplyStyle puts the layers back without registering them again`() =
        runTest {
            val (controller, renderer) = fixture()
            controller.add(listOf(state("circle")))
            val registered = controller.rasterLayerManager.getEntity("tiles")
            renderer.onMap.clear() // the style reload dropped it

            controller.reapplyStyle()

            assertEquals(state("circle").source, renderer.onMap["tiles"]?.source)
            assertSame(registered, controller.rasterLayerManager.getEntity("tiles"))
        }
}
