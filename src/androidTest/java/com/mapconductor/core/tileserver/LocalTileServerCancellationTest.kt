package com.mapconductor.core.tileserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The local server has to tell a map the truth about why it has no tile.
 *
 * "Not found" is a permanent answer, and a map that believes it leaves the
 * area showing whatever older zoom it still has -- which is what a patchwork
 * of two zoom levels on one screen looks like. A request the map itself
 * abandoned is not a missing tile and must not be answered as one.
 */
@RunWith(AndroidJUnit4::class)
class LocalTileServerCancellationTest {
    private val routeId = "cancellation-test"
    private lateinit var server: LocalTileServer

    /** A one-pixel PNG, enough for the server to have something to send. */
    private val png =
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte(),
        )

    private class SlowProvider(
        private val png: ByteArray,
        private val started: CountDownLatch,
    ) : TileProviderInterface {
        val cancelled = AtomicInteger()

        override fun renderTile(request: TileRequest): ByteArray = png

        override fun renderTile(
            request: TileRequest,
            isCancelled: () -> Boolean,
        ): ByteArray? {
            started.countDown()
            // Long enough for the caller to give up while this "renders".
            repeat(40) {
                Thread.sleep(50)
                if (isCancelled()) {
                    cancelled.incrementAndGet()
                    return null
                }
            }
            return png
        }
    }

    @Before
    fun setUp() {
        server = TileServerRegistry.get()
    }

    @After
    fun tearDown() {
        server.unregister(routeId)
    }

    private fun tileUrl(z: Int): URI {
        val template =
            server
                .urlTemplate(routeId, 512)
                .replace("{z}", "$z")
                .replace("{x}", "0")
                .replace("{y}", "0")
        return URI(template)
    }

    /**
     * One response, read as bytes.
     *
     * A tile body is a PNG, and a character reader turns bytes into however
     * many characters they happen to decode to -- which is not the number the
     * Content-Length promised, so the next read waits forever for a byte that
     * has already arrived.
     */
    private class Response(
        val status: String,
        val body: ByteArray,
    )

    private fun readResponse(input: InputStream): Response? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte == -1) return null
            head.append(byte.toChar())
        }
        val lines = head.toString().split("\r\n")
        val length =
            lines
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
                ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n <= 0) break
            read += n
        }
        return Response(lines.first(), body.copyOf(read))
    }

    private fun statusOf(url: URI): String {
        Socket(url.host, url.port).use { socket ->
            socket.soTimeout = 15_000
            socket.getOutputStream().write(
                "GET ${url.path} HTTP/1.1\r\nHost: ${url.host}\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            socket.getOutputStream().flush()
            return readResponse(socket.getInputStream())?.status ?: ""
        }
    }

    /**
     * A request the client walks away from must not come back as 404. There is
     * nobody left to read the answer, so the only thing that matters is what
     * the server decides the tile *is* -- and it is not missing.
     */
    @Test
    fun aTileTheClientAbandonedIsNotReportedMissing() {
        val started = CountDownLatch(1)
        val provider = SlowProvider(png, started)
        server.register(routeId, provider)

        val url = tileUrl(10)
        val socket = Socket(url.host, url.port)
        socket.getOutputStream().write(
            "GET ${url.path} HTTP/1.1\r\nHost: ${url.host}\r\n\r\n".toByteArray(),
        )
        socket.getOutputStream().flush()
        assertTrue("the render never started", started.await(10, TimeUnit.SECONDS))
        socket.close()

        // The provider should notice and give up rather than draw a tile
        // nobody is waiting for.
        val deadline = System.currentTimeMillis() + 10_000
        while (provider.cancelled.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals("the abandoned render was not cancelled", 1, provider.cancelled.get())

        // And the tile is still perfectly available to whoever asks next.
        assertTrue(statusOf(tileUrl(11)).contains("200"))
    }

    /**
     * The liveness check reads from the very stream the request parser reads,
     * so it has to put back whatever it takes. Two requests down one
     * connection is where that goes wrong.
     */
    @Test
    fun keepAliveStillWorksAlongsideTheLivenessCheck() {
        server.register(
            routeId,
            object : TileProviderInterface {
                override fun renderTile(request: TileRequest): ByteArray = png
            },
        )

        val url = tileUrl(10)
        Socket(url.host, url.port).use { socket ->
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            repeat(2) { index ->
                val path = url.path.replace("/512/10/", "/512/1$index/")
                out.write("GET $path HTTP/1.1\r\nHost: ${url.host}\r\n\r\n".toByteArray())
                out.flush()

                val response = readResponse(input)
                assertTrue("request $index got nothing", response != null)
                assertTrue("request $index got ${response!!.status}", response.status.contains("200"))
                assertEquals("request $index body", png.size, response.body.size)
            }
        }
    }
}
