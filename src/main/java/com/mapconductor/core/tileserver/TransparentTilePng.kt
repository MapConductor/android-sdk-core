package com.mapconductor.core.tileserver

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * A fully transparent tile, encoded once per pixel size and reused.
 *
 * This is what the server answers when a provider has nothing to draw. "No
 * markers here" and "no such tile" are different answers, and map SDKs
 * remember them differently: a 404 is taken as permanent, so a tile that was
 * empty for a moment — data still loading, a layer mid-switch — leaves a hole
 * that is never asked about again, with the neighbouring tiles' overhang cut
 * off at its edge. A transparent picture is the truthful answer, and it costs
 * a few hundred bytes once.
 *
 * Written by hand rather than through [android.graphics.Bitmap] so it does not
 * allocate a bitmap it never draws into, and so plain JVM tests can decode it.
 */
internal object TransparentTilePng {
    private val cache = HashMap<Int, ByteArray>()

    fun bytes(size: Int): ByteArray {
        val clamped = size.coerceIn(1, MAX_SIZE)
        synchronized(cache) {
            return cache.getOrPut(clamped) { encode(clamped) }
        }
    }

    private fun encode(size: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        val ihdr = ByteArray(13)
        writeIntBE(ihdr, 0, size)
        writeIntBE(ihdr, 4, size)
        ihdr[8] = 8 // bit depth
        ihdr[9] = 6 // colour type: RGBA
        writeChunk(out, "IHDR", ihdr)

        // Each row is a zero filter byte followed by RGBA pixels, all zero —
        // which is exactly what a fresh ByteArray is.
        val raw = ByteArray(size * (1 + size * 4))
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(raw)
        deflater.finish()
        val idat = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            idat.write(buffer, 0, n)
        }
        deflater.end()
        writeChunk(out, "IDAT", idat.toByteArray())

        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(
        out: ByteArrayOutputStream,
        type: String,
        data: ByteArray,
    ) {
        val length = ByteArray(4)
        writeIntBE(length, 0, data.size)
        out.write(length)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcBytes = ByteArray(4)
        writeIntBE(crcBytes, 0, crc.value.toInt())
        out.write(crcBytes)
    }

    private fun writeIntBE(
        target: ByteArray,
        at: Int,
        value: Int,
    ) {
        target[at] = (value ushr 24).toByte()
        target[at + 1] = (value ushr 16).toByte()
        target[at + 2] = (value ushr 8).toByte()
        target[at + 3] = value.toByte()
    }

    // Big enough for a 4096px tile; anything larger is a corrupt request, and
    // the row buffer above is quadratic in this.
    private const val MAX_SIZE = 4096
}
