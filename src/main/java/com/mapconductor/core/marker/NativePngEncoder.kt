package com.mapconductor.core.marker

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PNG encoding in Rust, for tiles the SDK rasterises itself.
 *
 * `Bitmap.compress` is the dominant cost of a marker tile once the drawing is
 * fixed: on a Pixel 5a it spends 133-249 ms where this spends 28-40 ms. The
 * tiles are ephemeral payloads for a local tile server, so the encoder trades
 * ratio for speed and produces files roughly 1.7x larger — which never reach a
 * network.
 *
 * Every failure path falls back to the platform encoder rather than throwing.
 * A device whose ABI is not in the library, a bitmap in an unexpected format, a
 * panic in Rust: all of them mean a slower tile, never a broken one.
 */
internal object NativePngEncoder {

    /**
     * Scratch pixel buffer, one per rendering thread.
     *
     * `renderTile` runs concurrently across the tile server's threads, and a
     * tile at this SDK's densities is around 7 MB of RGBA — allocating that per
     * call would cost far more than the encoding it is meant to speed up.
     */
    private val scratch = ThreadLocal<ByteBuffer>()

    private val available: Boolean =
        try {
            System.loadLibrary("tile_png_jni")
            true
        } catch (error: UnsatisfiedLinkError) {
            // Expected on any ABI the library was not built for.
            false
        }

    /** Returns PNG bytes, or null if the native path is unavailable or declined. */
    fun encode(bitmap: Bitmap): ByteArray? {
        if (!available) return null
        // Any other config has a different byte layout, and guessing at one is
        // how a fast encoder produces wrong colours.
        if (bitmap.config != Bitmap.Config.ARGB_8888) return null

        val width = bitmap.width
        val height = bitmap.height
        val needed = width * height * 4
        if (width <= 0 || height <= 0) return null

        var buffer = scratch.get()
        if (buffer == null || buffer.capacity() < needed) {
            buffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
            scratch.set(buffer)
        }
        buffer.clear()
        bitmap.copyPixelsToBuffer(buffer)

        return try {
            nativeEncode(buffer, width, height, bitmap.isPremultiplied)
        } catch (error: UnsatisfiedLinkError) {
            null
        }
    }

    /**
     * Encodes a direct RGBA8 buffer, returning null on any failure.
     *
     * The buffer is un-premultiplied in place when [premultiplied] is set, so
     * its contents are not reusable afterwards.
     */
    @JvmStatic
    private external fun nativeEncode(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        premultiplied: Boolean,
    ): ByteArray?
}
