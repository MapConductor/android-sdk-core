package com.mapconductor.core.tileserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalTileServerTest {
    @Test
    fun parsesStandardTileCoordinate() {
        assertEquals(TileCoordinate(y = 100, pixelRatio = 1), parseTileCoordinate("100.png"))
    }

    @Test
    fun parsesTomTomRetinaTileCoordinate() {
        assertEquals(TileCoordinate(y = 100, pixelRatio = 2), parseTileCoordinate("100@2x.png"))
    }

    @Test
    fun parsesOtherDensityTileCoordinate() {
        assertEquals(TileCoordinate(y = 100, pixelRatio = 3), parseTileCoordinate("100@3x.png"))
    }

    @Test
    fun rejectsNonNumericTileYCoordinate() {
        assertNull(parseTileCoordinate("tile@2x.png"))
    }

    @Test
    fun rejectsUnsupportedTilePixelRatio() {
        assertNull(parseTileCoordinate("100@4x.png"))
    }
}
