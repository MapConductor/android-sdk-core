package com.mapconductor.core.tileserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class TransparentTilePngTest {
    @Test
    fun decodesToTheRequestedSizeFullyTransparent() {
        val png = TransparentTilePng.bytes(64)
        val image = ImageIO.read(ByteArrayInputStream(png))
        assertNotNull("not a decodable PNG", image)
        assertEquals(64, image.width)
        assertEquals(64, image.height)
        for (x in intArrayOf(0, 31, 63)) {
            for (y in intArrayOf(0, 31, 63)) {
                assertEquals("alpha at $x,$y", 0, image.getRGB(x, y) ushr 24)
            }
        }
    }

    @Test
    fun encodesOnceAndReuses() {
        assertSame(TransparentTilePng.bytes(256), TransparentTilePng.bytes(256))
    }

    @Test
    fun survivesAnAbsurdSizeByClamping() {
        val image = ImageIO.read(ByteArrayInputStream(TransparentTilePng.bytes(1_000_000)))
        assertEquals(4096, image.width)
    }
}
