package com.mapconductor.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things a provider would otherwise have to remember on its own,
 * and both of which look fine until they do not: a replaced set has to undo
 * what it dropped, and a style load wipes everything that was applied.
 */
class StyleMutationStoreTest {
    private val applied = mutableListOf<StyleMutation>()

    private fun store(unsupported: (StyleMutation) -> Boolean = { false }) =
        StyleMutationStore { mutations ->
            applied += mutations
            mutations.filter(unsupported)
        }

    private fun paint(
        layer: String,
        key: String,
        value: String,
        previous: String,
    ) = StyleMutation.SetPaint(layer, key, value, previous)

    @Test
    fun replacingASetPutsBackWhatTheNewOneNoLongerMentions() {
        val store = store()
        store.apply(
            listOf(
                paint("roads", "line-color", "\"#fff\"", "\"#888\""),
                paint("water", "fill-color", "\"#000\"", "\"#a0c8f0\""),
            ),
        )
        applied.clear()

        // The second set drops the water rule entirely.
        store.apply(listOf(paint("roads", "line-color", "\"#f00\"", "\"#888\"")))

        assertEquals(
            "water goes back to the style's own colour, then roads take the new one",
            listOf(
                paint("water", "fill-color", "\"#a0c8f0\"", "\"#000\""),
                paint("roads", "line-color", "\"#f00\"", "\"#888\""),
            ),
            applied,
        )
    }

    /**
     * The restore has to be sent before the new value, or a property that
     * both sets touch would be put back to the style's value after being
     * given its new one.
     */
    @Test
    fun restoresGoOutBeforeTheNewValues() {
        val store = store()
        store.apply(listOf(paint("a", "line-color", "\"#1\"", "\"#0\"")))
        applied.clear()
        store.apply(listOf(paint("b", "line-color", "\"#2\"", "\"#0\"")))

        assertEquals("a", applied.first().layerId)
        assertEquals("b", applied.last().layerId)
    }

    @Test
    fun clearPutsEverythingBack() {
        val store = store()
        store.apply(listOf(paint("roads", "line-color", "\"#fff\"", "\"#888\"")))
        applied.clear()

        store.clear()

        assertEquals(listOf(paint("roads", "line-color", "\"#888\"", "\"#fff\"")), applied)
        assertTrue(store.applied.isEmpty())
    }

    /**
     * A design change, a provider reloading, anything: the renderer comes
     * back with the document's values and nothing that was applied over
     * them. Without this the adjustment survives until the first basemap
     * switch and then quietly stops.
     */
    @Test
    fun aStyleLoadPutsTheWholeSetOnAgain() {
        val store = store()
        val mutations = listOf(paint("roads", "line-color", "\"#fff\"", "\"#888\""))
        store.apply(mutations)
        applied.clear()

        store.onStyleLoaded()

        assertEquals(mutations, applied)
    }

    @Test
    fun nothingAppliedMeansNothingToDoOnAStyleLoad() {
        val store = store()
        store.onStyleLoaded()
        assertTrue(applied.isEmpty())
    }

    /**
     * iOS MapLibre cannot set every spec key by name. The caller has to be
     * told which ones, rather than being left to assume the map looks the
     * way the rules asked.
     */
    @Test
    fun whatTheMapCouldNotDoComesBack() {
        val store = store(unsupported = { (it as? StyleMutation.SetPaint)?.key == "line-gradient" })
        val failed =
            store.apply(
                listOf(
                    paint("roads", "line-color", "\"#fff\"", "\"#888\""),
                    paint("roads", "line-gradient", "[\"interpolate\"]", "null"),
                ),
            )
        assertEquals(1, failed.size)
        assertEquals("line-gradient", (failed.single() as StyleMutation.SetPaint).key)
    }
}
