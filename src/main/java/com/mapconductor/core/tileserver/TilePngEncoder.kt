package com.mapconductor.core.tileserver

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PNG encoding in Rust, for tiles the SDK rasterises itself.
 *
 * Worth having because the platform encoders vary enormously: on a Pixel 5a
 * `Bitmap.compress` spends 133-165 ms on a tile this encodes in 7-14 ms. The
 * tiles are ephemeral payloads for a local tile server, so it trades ratio for
 * speed and produces files roughly 2-3x larger — which never reach a network,
 * though they do take proportionally more room in a tile cache.
 *
 * Lives in the core so there is one copy of the encoder in an app rather than
 * one per module that draws tiles.
 *
 * Every failure path returns null rather than throwing. A device whose ABI is
 * not in the library, a bitmap in an unexpected format, a panic in Rust: all of
 * them should mean a slower tile, never a broken one, so every caller is
 * expected to have a platform fallback.
 */
object TilePngEncoder {

    /**
     * Scratch pixel buffer, one per calling thread.
     *
     * Tile rendering runs concurrently across the tile server's threads, and a
     * tile at this SDK's densities is around 7 MB of RGBA — allocating that per
     * call would cost more than the encoding it is meant to speed up.
     */
    private val scratch = ThreadLocal<ByteBuffer>()

    /** False where the native library is missing for this device's ABI. */
    val isAvailable: Boolean =
        try {
            System.loadLibrary("tile_png_jni")
            true
        } catch (error: UnsatisfiedLinkError) {
            false
        }

    /**
     * Encodes a bitmap. Returns null if the native path is unavailable or
     * declined the input, in which case use `Bitmap.compress`.
     */
    fun encode(bitmap: Bitmap): ByteArray? {
        if (!isAvailable) return null
        // Any other config has a different byte layout, and guessing at one is
        // how a fast encoder produces wrong colours.
        if (bitmap.config != Bitmap.Config.ARGB_8888) return null

        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val needed = width * height * 4

        var buffer = scratch.get()
        if (buffer == null || buffer.capacity() < needed) {
            buffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
            scratch.set(buffer)
        }
        buffer.clear()
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()

        return encode(buffer, width, height, premultiplied = bitmap.isPremultiplied)
    }

    /**
     * Encodes RGBA8 pixels from a **direct** buffer, whose pointer crosses JNI
     * without a copy.
     *
     * Set [premultiplied] when the colour channels are scaled by alpha — an
     * `ARGB_8888` bitmap's are, a `glReadPixels` result's usually are not. PNG
     * stores straight alpha, so encoding premultiplied pixels unchanged darkens
     * every partly transparent one. The buffer is un-premultiplied in place
     * when this is set, so its contents are not reusable afterwards.
     */
    fun encode(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        premultiplied: Boolean,
    ): ByteArray? {
        if (!isAvailable) return null
        if (width <= 0 || height <= 0) return null
        if (!buffer.isDirect) return null
        if (buffer.capacity() < width * height * 4) return null

        return try {
            nativeEncode(buffer, width, height, premultiplied)
        } catch (error: UnsatisfiedLinkError) {
            null
        }
    }

    @JvmStatic
    private external fun nativeEncode(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        premultiplied: Boolean,
    ): ByteArray?
}
