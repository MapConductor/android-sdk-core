package com.mapconductor.core.map

import com.mapconductor.core.features.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 省略した余白の辺だけが現在のカメラから引き継がれ、明示された 0 はそのまま使われることを確認する。
 *
 * ios-sdk / js-sdk の同名テストと同じ観点。
 */
class PartialPaddingsTest {
    private val tokyo = GeoPoint(latitude = 35.6812, longitude = 139.7671)
    private val current =
        MapCameraPosition(
            position = tokyo,
            zoom = 12.0,
            bearing = 30.0,
            tilt = 45.0,
            paddings = MapPaddings(10.0, 20.0, 30.0, 40.0),
        )

    private fun MapPaddingsInterface?.sides() = listOf(this!!.top, left, bottom, right)

    @Test
    fun unsetSidesCopyFromCurrent() {
        val resolved =
            MapCameraPosition(position = tokyo, zoom = 12.0, bearing = 0.0, tilt = 0.0, paddings = MapPaddings(top = 300.0))
                .resolvedOnto(current)
        assertEquals(listOf(300.0, 20.0, 30.0, 40.0), resolved.paddings.sides())
        assertTrue((resolved.paddings as MapPaddings).isFullySpecified)
    }

    @Test
    fun allUnsetCopiesAll() {
        val resolved = MapCameraPosition(position = tokyo, paddings = MapPaddings()).resolvedOnto(current)
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0), resolved.paddings.sides())
    }

    @Test
    fun explicitZeroIsHonoured() {
        val resolved =
            MapCameraPosition(position = tokyo, paddings = MapPaddings(top = 0.0, right = 0.0)).resolvedOnto(current)
        assertEquals(listOf(0.0, 20.0, 30.0, 0.0), resolved.paddings.sides())
    }

    @Test
    fun omittedPaddingsStillMeanZeros() {
        val resolved = MapCameraPosition(position = tokyo).resolvedOnto(current)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), resolved.paddings.sides())
    }

    @Test
    fun unresolvedReadsZeroAndKeepsFlags() {
        val partial = MapPaddings(top = 300.0)
        assertEquals(listOf(300.0, 0.0, 0.0, 0.0), partial.sides())
        assertTrue(partial.isTopSpecified)
        assertFalse(partial.isLeftSpecified)
        assertFalse(partial.isBottomSpecified)
        assertFalse(partial.isRightSpecified)
        assertSame(partial, MapPaddings.from(partial))
        val copied = MapCameraPosition(position = tokyo, paddings = partial).copy(zoom = 5.0)
        assertFalse((copied.paddings as MapPaddings).isLeftSpecified)
    }

    @Test
    fun nullCurrentPaddingsFillWithZero() {
        val noPaddings = MapCameraPosition(position = tokyo, paddings = null)
        val resolved = MapCameraPosition(position = tokyo, paddings = MapPaddings(bottom = 50.0)).resolvedOnto(noPaddings)
        assertEquals(listOf(0.0, 0.0, 50.0, 0.0), resolved.paddings.sides())
    }

    @Test
    fun fastPathNeedsAllSides() {
        val full =
            MapCameraPosition(position = tokyo, zoom = 1.0, bearing = 0.0, tilt = 0.0, paddings = MapPaddings(1.0, 2.0, 3.0, 4.0))
        assertSame(full, full.resolvedOnto(current))
        val partial = MapCameraPosition(position = tokyo, zoom = 1.0, bearing = 0.0, tilt = 0.0, paddings = MapPaddings(top = 1.0))
        assertNotSame(partial, partial.resolvedOnto(current))
    }

    @Test
    fun fourArgumentConstructorIsFullySpecified() {
        assertTrue(MapPaddings(0.0, 0.0, 0.0, 0.0).isFullySpecified)
        assertTrue(MapPaddings.Zeros.isFullySpecified)
        assertTrue(MapPaddings(top = 1.0, left = 2.0, bottom = 3.0, right = 4.0).isFullySpecified)
    }
}
