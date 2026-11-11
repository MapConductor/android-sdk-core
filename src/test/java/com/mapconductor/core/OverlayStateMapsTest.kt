package com.mapconductor.core

import org.junit.Assert.*
import org.junit.Test

class OverlayStateMapsTest {
    @Test
    fun generationsKeepValuesAndInsertionOrderIndependent() {
        val first = copyOverlayStates(mapOf("a" to Any(), "b" to Any(), "c" to Any()))
        val second = copyOverlayStates(first)
        second.remove("b")
        second["d"] = Any()
        val third = copyOverlayStates(second)
        third["b"] = first.getValue("b")
        assertEquals(listOf("a", "b", "c"), first.keys.toList())
        assertEquals(listOf("a", "c", "d"), second.keys.toList())
        assertEquals(listOf("a", "c", "d", "b"), third.keys.toList())
        assertSame(first["a"], second["a"])
        assertSame(second["a"], third["a"])
    }
}
