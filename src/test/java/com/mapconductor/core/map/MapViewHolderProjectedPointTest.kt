package com.mapconductor.core.map

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.projection.MapCoordinateSystem
import com.mapconductor.core.projection.ProjectedPoint
import com.mapconductor.core.projection.ProjectedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 投影座標の既定実装。上書きしないホルダは EPSG:3857 メートルを返す。
 */
class MapViewHolderProjectedPointTest {
    private class PlainHolder : MapViewHolderInterface<Unit, Unit> {
        override val mapView = Unit
        override val map = Unit

        override fun toScreenOffset(position: GeoPointInterface): Offset? = null
    }

    @Test
    fun defaultCoordinateSystemIsWebMercator() {
        val system = PlainHolder().coordinateSystem
        assertEquals("EPSG:3857", system.code)
        assertEquals(ProjectedUnit.Meters, system.unit)
        assertEquals(MapCoordinateSystem.WebMercator, system)
    }

    @Test
    fun defaultProjectionIsEpsg3857Meters() {
        // 東京駅。期待値は x = λ·a、y = a·ln(tan(π/4 + φ/2))（a = 6378137）。
        val point = PlainHolder().toProjectedPoint(GeoPoint(35.681236, 139.767125, 0.0))
        assertNotNull(point)
        assertEquals(15558805.18, point!!.x, 0.01)
        assertEquals(4256848.12, point.y, 0.01)
    }

    @Test
    fun defaultRoundTripReturnsTheSamePoint() {
        val holder = PlainHolder()
        val original = GeoPoint(-33.8688, 151.2093, 0.0)
        val back = holder.fromProjectedPoint(holder.toProjectedPoint(original)!!)
        assertNotNull(back)
        assertEquals(original.latitude, back!!.latitude, 1e-9)
        assertEquals(original.longitude, back.longitude, 1e-9)
    }

    @Test
    fun originMapsToNullIsland() {
        val back = PlainHolder().fromProjectedPoint(ProjectedPoint(0.0, 0.0))
        assertEquals(0.0, back!!.latitude, 1e-12)
        assertEquals(0.0, back.longitude, 1e-12)
    }
}
