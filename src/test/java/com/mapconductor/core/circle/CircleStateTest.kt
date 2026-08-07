package com.mapconductor.core.circle

import com.mapconductor.core.features.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CircleStateTest {
    @Test
    fun equals_sameProperties_areEqual() {
        val a = CircleState(center = GeoPoint(35.0, 139.0), radiusMeters = 100.0)
        val b = CircleState(center = GeoPoint(35.0, 139.0), radiusMeters = 100.0)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_differentProperties_areNotEqual() {
        val a = CircleState(center = GeoPoint(35.0, 139.0), radiusMeters = 100.0)
        val b = CircleState(center = GeoPoint(35.0, 139.0), radiusMeters = 200.0)
        assertNotEquals(a, b)
    }

    @Test
    fun equals_nonCircleState_isNotEqual() {
        val a = CircleState(center = GeoPoint(35.0, 139.0), radiusMeters = 100.0)
        assertNotEquals(a, "not a circle")
    }
}
