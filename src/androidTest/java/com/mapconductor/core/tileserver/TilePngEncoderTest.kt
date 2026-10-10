package com.mapconductor.core.tileserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color

/**
 * On-device checks for the Rust PNG encoder.
 *
 * Instrumented rather than local because the thing most likely to be wrong is
 * not the Kotlin: it is whether `libtile_png_jni.so` is packaged for the
 * device's ABI at all. A JVM test would pass with no native library present,
 * since every failure path here deliberately returns null.
 */
@RunWith(AndroidJUnit4::class)
class TilePngEncoderTest {
    /**
     * The whole point of putting the library in the core: it has to load in an
     * ordinary consumer process, with no help from the caller.
     *
     * If this fails, everything below it passes vacuously — the encoder would
     * return null and the platform fallback would quietly cover for it.
     */
    @Test
    fun libraryLoadsOnThisDevice() {
        assertTrue(
            "libtile_png_jni.so did not load — check jniLibs carries this device's ABI",
            TilePngEncoder.isAvailable,
        )
    }

    /**
     * Opaque pixels, where premultiplication is the identity, so the round trip
     * has to be exact. This is the test that catches a swapped R and B: the
     * classic way a fast encoder produces a plausible-looking wrong image.
     */
    @Test
    fun opaquePixelsSurviveExactly() {
        val source = opaqueFixture()
        val png = TilePngEncoder.encode(source)
        assertNotNull("encoder declined an ARGB_8888 bitmap", png)

        val decoded = decode(png!!)
        assertEquals(source.width, decoded.width)
        assertEquals(source.height, decoded.height)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                assertEquals(
                    "pixel ($x,$y)",
                    source.getPixel(x, y),
                    decoded.getPixel(x, y),
                )
            }
        }
    }

    /**
     * Partly transparent pixels against the encoder this one replaces.
     *
     * Both paths undo the same premultiplication, so they may round a channel
     * differently, but they must not disagree about the image. Alpha carries no
     * rounding at all and is compared exactly; colour is allowed one step, which
     * is what integer un-premultiplication can legitimately cost.
     */
    @Test
    fun transparentPixelsAgreeWithBitmapCompress() {
        val source = translucentFixture()

        val rust = decode(TilePngEncoder.encode(source)!!)
        val platform = decode(compress(source))

        var worstColourStep = 0
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val a = rust.getPixel(x, y)
                val b = platform.getPixel(x, y)
                assertEquals("alpha at ($x,$y)", Color.alpha(b), Color.alpha(a))
                // Fully transparent pixels carry no recoverable colour: both
                // encoders are free to write anything under alpha 0.
                if (Color.alpha(b) == 0) continue
                val step =
                    maxOf(
                        abs(Color.red(a) - Color.red(b)),
                        abs(Color.green(a) - Color.green(b)),
                        abs(Color.blue(a) - Color.blue(b)),
                    )
                assertTrue("colour at ($x,$y) differs by $step", step <= 1)
                worstColourStep = maxOf(worstColourStep, step)
            }
        }
        println("TILEPNG translucent round trip: worst colour step $worstColourStep")
    }

    /**
     * A bitmap the encoder cannot read has to come back as null, because the
     * caller's fallback is the only thing standing between that and a blank
     * tile. RGB_565 has a different byte layout entirely.
     */
    @Test
    fun unsupportedConfigDeclinesInsteadOfGuessing() {
        val source = opaqueFixture().copy(Bitmap.Config.RGB_565, false)
        assertNull(TilePngEncoder.encode(source))
    }

    /** A heap buffer's address does not survive the JNI crossing. */
    @Test
    fun indirectBufferIsRefused() {
        val heap = ByteBuffer.allocate(TILE * TILE * 4).order(ByteOrder.nativeOrder())
        assertNull(TilePngEncoder.encode(heap, TILE, TILE, premultiplied = false))
    }

    /**
     * Not an assertion about speed — the numbers vary with the device and
     * nothing here should fail because a phone was busy. It prints the
     * comparison so the reason this encoder exists stays visible in the log.
     *
     * Deliberately run on noise rather than on the smooth fixtures above.
     * Synthetic tiles mislead badly here: a flat colour under an alpha ramp
     * compresses to a couple of kilobytes, which flatters `compress` on size
     * and starves it of the work this encoder is meant to save. Noise is not
     * realistic either, only wrong in the opposite direction — the honest
     * comparison, on tiles the marker renderer actually produced, is
     * `MarkerTileCostTest` in android-vectortile.
     */
    @Test
    fun reportsCostAgainstThePlatformEncoder() {
        val source = noiseFixture()

        val platformSamples = ArrayList<Double>(REPEATS)
        var platformBytes = 0
        repeat(REPEATS) {
            val started = System.nanoTime()
            platformBytes = compress(source).size
            platformSamples.add((System.nanoTime() - started) / 1_000_000.0)
        }

        val rustSamples = ArrayList<Double>(REPEATS)
        var rustBytes = 0
        repeat(REPEATS) {
            val started = System.nanoTime()
            rustBytes = TilePngEncoder.encode(source)!!.size
            rustSamples.add((System.nanoTime() - started) / 1_000_000.0)
        }

        println(
            "TILEPNG %dx%d compress=%.1fms/%dB rust=%.1fms/%dB".format(
                TILE, TILE,
                median(platformSamples), platformBytes,
                median(rustSamples), rustBytes,
            ),
        )
    }

    private fun opaqueFixture(): Bitmap {
        val bitmap = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        for (y in 0 until TILE) {
            for (x in 0 until TILE) {
                // Distinct per channel, so a channel swap cannot coincide.
                bitmap.setPixel(x, y, Color.argb(255, x % 256, y % 256, (x + 2 * y) % 256))
            }
        }
        return bitmap
    }

    private fun translucentFixture(): Bitmap {
        val bitmap = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        for (y in 0 until TILE) {
            for (x in 0 until TILE) {
                bitmap.setPixel(x, y, Color.argb((x * 3) % 256, 240, 90, 30))
            }
        }
        return bitmap
    }

    /** Incompressible content, from a fixed seed so runs stay comparable. */
    private fun noiseFixture(): Bitmap {
        val bitmap = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(TILE * TILE)
        var state = 0x2545F491
        for (index in pixels.indices) {
            state = state * 1_664_525 + 1_013_904_223
            val bits = state ushr 8
            pixels[index] =
                Color.argb(
                    128 + (bits and 0x7F),
                    (bits ushr 7) and 0xFF,
                    (bits ushr 15) and 0xFF,
                    (bits ushr 23) and 0xFF,
                )
        }
        bitmap.setPixels(pixels, 0, TILE, 0, 0, TILE, TILE)
        return bitmap
    }

    private fun compress(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream(TILE * TILE)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private fun decode(png: ByteArray): Bitmap =
        BitmapFactory
            .decodeByteArray(png, 0, png.size)
            .copy(Bitmap.Config.ARGB_8888, false)

    private fun median(samples: List<Double>): Double = samples.sorted()[samples.size / 2]

    private companion object {
        const val TILE = 512
        const val REPEATS = 5
    }
}
