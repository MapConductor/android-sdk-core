package com.mapconductor.core.tileserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.net.Socket
import java.net.URI
import android.graphics.BitmapFactory

/**
 * What the server answers when a provider has no tile to give.
 *
 * The three answers mean different things to a map SDK, and mixing them up
 * shows on screen: a 404 is remembered as permanent, so a transiently empty
 * tile answered with it becomes a hole that is never asked about again, with
 * the neighbours' overhang cut off at its edge. Empty gets a transparent
 * picture, a thrown render gets a retryable 503, and 404 is reserved for
 * requests that name nothing. The iOS LocalTileServer answers the same way.
 */
@RunWith(AndroidJUnit4::class)
class LocalTileServerSemanticsTest {
    private val routeId = "semantics-test"
    private lateinit var server: LocalTileServer

    @Before
    fun setUp() {
        server = TileServerRegistry.get()
    }

    @After
    fun tearDown() {
        server.unregister(routeId)
    }

    private class Response(
        val status: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    // Bytes, not a character reader: a PNG body decoded as characters no
    // longer matches its Content-Length. Same shape as the reader in
    // LocalTileServerCancellationTest.
    private fun readResponse(input: InputStream): Response? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte == -1) return null
            head.append(byte.toChar())
        }
        val lines = head.toString().split("\r\n")
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val at = line.indexOf(':')
            if (at > 0) headers[line.substring(0, at).trim().lowercase()] = line.substring(at + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n <= 0) break
            read += n
        }
        return Response(lines.first(), headers, body.copyOf(read))
    }

    private fun fetch(url: URI): Response {
        Socket(url.host, url.port).use { socket ->
            socket.soTimeout = 15_000
            socket.getOutputStream().write(
                "GET ${url.path} HTTP/1.1\r\nHost: ${url.host}\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            socket.getOutputStream().flush()
            val response = readResponse(socket.getInputStream())
            assertNotNull("no response at all", response)
            return response!!
        }
    }

    private fun tileUrl(
        z: Int,
        route: String = routeId,
    ): URI {
        val template =
            server
                .urlTemplate(route, 512)
                .replace("{z}", "$z")
                .replace("{x}", "0")
                .replace("{y}", "0")
        return URI(template)
    }

    /** A route nobody registered names nothing; only that earns a 404. */
    @Test
    fun anUnknownRouteIs404() {
        assertTrue(fetch(tileUrl(10, route = "no-such-route")).status.contains("404"))
    }

    /** An empty spot is a real answer: a transparent tile, not a 404. */
    @Test
    fun anEmptyTileIsATransparentPicture() {
        server.register(
            routeId,
            object : TileProviderInterface {
                override fun renderTile(request: TileRequest): ByteArray? = null
            },
        )
        val response = fetch(tileUrl(10))
        assertTrue("got ${response.status}", response.status.contains("200"))
        val bitmap = BitmapFactory.decodeByteArray(response.body, 0, response.body.size)
        assertNotNull("body is not a decodable PNG", bitmap)
        assertEquals(512, bitmap.width)
        assertEquals(512, bitmap.height)
        assertEquals("corner pixel", 0, bitmap.getPixel(0, 0))
        assertEquals("centre pixel", 0, bitmap.getPixel(256, 256))
    }

    /** A retina path gets a transparent tile at the density it asked for. */
    @Test
    fun anEmptyRetinaTileMatchesItsDensity() {
        server.register(
            routeId,
            object : TileProviderInterface {
                override fun renderTile(request: TileRequest): ByteArray? = null
            },
        )
        val base = tileUrl(10)
        val response = fetch(URI(base.toString().replace("0.png", "0@2x.png")))
        val bitmap = BitmapFactory.decodeByteArray(response.body, 0, response.body.size)
        assertNotNull(bitmap)
        assertEquals(1024, bitmap.width)
    }

    /** A provider that throws gets a 503 the map knows to retry — never 404. */
    @Test
    fun aRenderFailureIs503WithRetryAfter() {
        server.register(
            routeId,
            object : TileProviderInterface {
                override fun renderTile(request: TileRequest): ByteArray = throw IllegalStateException("boom")
            },
        )
        val response = fetch(tileUrl(10))
        assertTrue("got ${response.status}", response.status.contains("503"))
        assertEquals("1", response.headers["retry-after"])
        assertTrue(
            "cache-control was ${response.headers["cache-control"]}",
            response.headers["cache-control"]?.contains("no-store") == true,
        )
        // And the connection is still fine for the next request.
        server.unregister(routeId)
        assertTrue(fetch(tileUrl(11)).status.contains("404"))
    }
}
