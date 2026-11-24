package com.mapconductor.core.tileserver

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tiles waiting on the network must not hold back tiles that are on the device.
 *
 * Reported from a mall Wi-Fi that connected and then answered nothing: with
 * an offline package for the area, the map stayed white, the area included.
 * The vector tile provider reads the package at once, but every tile is drawn
 * on one of the server's workers, and a tile waiting for its sources held its
 * worker for as long as it waited. A screen of those took every worker, and
 * the package tile queued behind them.
 */
@RunWith(AndroidJUnit4::class)
class LocalTileServerPrepareTest {
    private val waitingRoute = "prepare-test-network"
    private val localRoute = "prepare-test-device"
    private lateinit var server: LocalTileServer
    private val clients = Executors.newFixedThreadPool(32)

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

    /**
     * Like the vector tile provider on a network that is not answering: its
     * sources take [waitMs] to fetch. Fetched in [prepareTile] when the server
     * asks; otherwise the render has to wait for them itself.
     */
    private inner class WaitingProvider(
        private val waitMs: Long,
    ) : TileProviderInterface {
        private val prepared = Collections.synchronizedSet(HashSet<TileRequest>())

        override fun prepareTile(
            request: TileRequest,
            isCancelled: () -> Boolean,
        ) {
            Thread.sleep(waitMs)
            prepared.add(request)
        }

        override fun renderTile(request: TileRequest): ByteArray {
            if (!prepared.remove(request)) Thread.sleep(waitMs)
            return png
        }
    }

    @Before
    fun setUp() {
        server = TileServerRegistry.get()
    }

    @After
    fun tearDown() {
        clients.shutdownNow()
        server.unregister(waitingRoute)
        server.unregister(localRoute)
    }

    private fun tileUrl(
        route: String,
        x: Int,
    ): URI =
        URI(
            server
                .urlTemplate(route, 256)
                .replace("{z}", "10")
                .replace("{x}", "$x")
                .replace("{y}", "0"),
        )

    private fun get(url: URI): String {
        Socket(url.host, url.port).use { socket ->
            socket.soTimeout = 30_000
            socket.getOutputStream().write(
                "GET ${url.path} HTTP/1.1\r\nHost: ${url.host}\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            socket.getOutputStream().flush()
            return socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine() ?: ""
        }
    }

    @Test
    fun aTileOnTheDeviceIsNotQueuedBehindTilesWaitingOnTheNetwork() {
        server.register(waitingRoute, WaitingProvider(waitMs = 4_000))
        server.register(
            localRoute,
            object : TileProviderInterface {
                override fun renderTile(request: TileRequest): ByteArray = png
            },
        )

        // A screen's worth of tiles around the area, more than the server
        // has workers, every one waiting four seconds on the network.
        repeat(24) { x -> clients.execute { get(tileUrl(waitingRoute, x)) } }
        Thread.sleep(500)

        val started = System.nanoTime()
        val status = get(tileUrl(localRoute, 0))
        val seconds = (System.nanoTime() - started) / 1e9
        Log.i(TAG, "device tile took ${"%.2f".format(seconds)}s status=$status")
        assertTrue("got $status", status.contains("200"))
        assertTrue("the device tile waited ${seconds}s behind the network", seconds < 1.0)

        // And the waiting ones still arrive.
        clients.shutdown()
        assertTrue(clients.awaitTermination(30, TimeUnit.SECONDS))
    }

    private companion object {
        const val TAG = "LocalTileServerPrepareTest"
    }
}
