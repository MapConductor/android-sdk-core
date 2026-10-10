package com.mapconductor.core.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The parts of [StyleMutation] that need a real `org.json`, which the JVM
 * unit tests do not have: the stub in the Android jar throws on every call,
 * so reading what the style compiler wrote has to be checked on a device.
 */
@RunWith(AndroidJUnit4::class)
class StyleMutationParseTest {
    @Test
    fun readsWhatTheCompilerWrites() {
        val parsed =
            StyleMutation.parseList(
                """
                [
                  {"op":"setPaint","layerId":"roads","key":"line-color","value":"#fff","previous":null},
                  {"op":"setLayout","layerId":"labels","key":"visibility","value":"none","previous":"visible"},
                  {"op":"setFilter","layerId":"roads","value":["==","class","motorway"],"previous":null},
                  {"op":"setZoomRange","layerId":"roads","minZoom":5,"maxZoom":null,
                   "previousMinZoom":null,"previousMaxZoom":null},
                  {"op":"addLayer","layerId":"bg","layer":{"id":"bg","type":"background"},"beforeId":"water"},
                  {"op":"somethingFromTheFuture","layerId":"roads"}
                ]
                """.trimIndent(),
            )

        assertEquals("the unknown op is dropped, the rest survive", 5, parsed.size)
        // A string value keeps its quotes, or no renderer could read it back.
        assertEquals("\"#fff\"", (parsed[0] as StyleMutation.SetPaint).value)
        assertEquals("null", (parsed[0] as StyleMutation.SetPaint).previous)
        assertEquals("\"none\"", (parsed[1] as StyleMutation.SetLayout).value)
        assertEquals("[\"==\",\"class\",\"motorway\"]", (parsed[2] as StyleMutation.SetFilter).value)
        assertEquals(5.0, (parsed[3] as StyleMutation.SetZoomRange).minZoom)
        assertEquals(null, (parsed[3] as StyleMutation.SetZoomRange).maxZoom)
        assertEquals("bg", parsed[4].layerId)
        assertEquals("water", (parsed[4] as StyleMutation.AddLayer).beforeId)
    }

    /** Rubbish in must not take the map down; it means no adjustment. */
    @Test
    fun answersWithNothingRatherThanThrowing() {
        assertEquals(emptyList<StyleMutation>(), StyleMutation.parseList("not json"))
        assertEquals(emptyList<StyleMutation>(), StyleMutation.parseList("{}"))
        assertEquals(emptyList<StyleMutation>(), StyleMutation.parseList("[{\"op\":\"setPaint\"}]"))
    }

    @Test
    fun addingALayerIsUndoneByRemovingIt() {
        val add = StyleMutation.AddLayer("bg", """{"id":"bg","type":"background"}""", beforeId = null)
        assertEquals(StyleMutation.RemoveLayer("bg"), add.reversed())
    }

    /**
     * Applying a set and then its reverse has to leave the map where it
     * started, whatever the values were.
     */
    @Test
    fun everyMutationUndoesItself() {
        val mutations =
            StyleMutation.parseList(
                """
                [
                  {"op":"setPaint","layerId":"a","key":"line-color","value":"#fff","previous":"#888"},
                  {"op":"setLayout","layerId":"b","key":"visibility","value":"none","previous":null},
                  {"op":"setFilter","layerId":"c","value":["all"],"previous":null},
                  {"op":"setZoomRange","layerId":"d","minZoom":5,"maxZoom":12,
                   "previousMinZoom":null,"previousMaxZoom":22}
                ]
                """.trimIndent(),
            )
        for (mutation in mutations) {
            val there = mutation.reversed()!!
            val andBack = there.reversed()!!
            assertEquals("${mutation.target} did not survive a round trip", mutation, andBack)
        }
    }
}
