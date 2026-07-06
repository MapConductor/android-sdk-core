package com.mapconductor.core.tileserver

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import android.util.Log

class LocalTileServer private constructor(
    private val serverSocket: ServerSocket,
    forceNoStoreCache: Boolean,
) {
    private val providers = ConcurrentHashMap<String, TileProviderInterface>()
    private val loggedRoutes = ConcurrentHashMap.newKeySet<String>()
    private val running = AtomicBoolean(false)
    private val acceptThread = Thread { acceptLoop() }
    private val shedConnections = AtomicLong(0)

    // Bounded worker pool: excess connections beyond the queue capacity are
    // rejected and closed instead of spawning unbounded threads.
    private val clientExecutor =
        ThreadPoolExecutor(
            MAX_WORKER_THREADS,
            MAX_WORKER_THREADS,
            WORKER_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(MAX_QUEUED_CONNECTIONS),
            { runnable -> Thread(runnable).apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }

    @Volatile
    private var forceNoStoreCache: Boolean = forceNoStoreCache

    val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

    fun setForceNoStoreCache(value: Boolean) {
        forceNoStoreCache = value
    }

    fun register(
        routeId: String,
        provider: TileProviderInterface,
    ) {
        providers[routeId] = provider
    }

    fun unregister(routeId: String) {
        providers.remove(routeId)
    }

    fun urlTemplate(
        routeId: String,
        tileSize: Int,
    ): String = "$baseUrl/tiles/$routeId/$tileSize/{z}/{x}/{y}.png"

    fun urlTemplate(
        routeId: String,
        tileSize: Int,
        cacheKey: String,
    ): String = "$baseUrl/tiles/$routeId/$tileSize/$cacheKey/{z}/{x}/{y}.png"

    /**
     * URL template with cache key as query parameter instead of path segment.
     * Some map SDKs (like HERE) may have stricter URL template parsing.
     */
    fun urlTemplateWithQueryCacheKey(
        routeId: String,
        tileSize: Int,
        cacheKey: String,
    ): String = "$baseUrl/tiles/$routeId/$tileSize/{z}/{x}/{y}.png?v=$cacheKey"

    /**
     * Whether the server is accepting connections. A stopped server cannot be
     * restarted (its socket and worker pool are closed) — create a new one.
     */
    fun isRunning(): Boolean = running.get()

    fun start() {
        if (running.compareAndSet(false, true)) {
            acceptThread.isDaemon = true
            acceptThread.start()
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            serverSocket.close()
            clientExecutor.shutdownNow()
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket =
                try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    if (running.get()) {
                        continue
                    }
                    return
                }
            // Only serve device-internal clients: a remote peer cannot complete
            // a TCP handshake with a spoofed loopback source address.
            if (socket.inetAddress?.isLoopbackAddress != true) {
                Log.w(TAG, "Rejected non-loopback connection from ${socket.inetAddress}")
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                continue
            }
            try {
                clientExecutor.execute { handleClient(socket) }
            } catch (_: RejectedExecutionException) {
                // Saturated: shed the connection; the map SDK will retry the tile.
                val shed = shedConnections.incrementAndGet()
                Log.w(
                    TAG,
                    "Shed connection (pool saturated) total=$shed " +
                        "active=${clientExecutor.activeCount} queued=${clientExecutor.queue.size}",
                )
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                var handled = 0
                while (handled < MAX_KEEP_ALIVE_REQUESTS) {
                    val request = readRequest(reader) ?: break
                    if (!request.valid) {
                        writeResponse(client, "400 Bad Request", "text/plain", "Bad request".toByteArray())
                        break
                    }

                    val startNs = System.nanoTime()
                    val method = request.method
                    val path = request.path.substringBefore('?').trim('/')
                    val keepAlive = shouldKeepAlive(request)
                    Log.d("Server", "---->${path}")

                    if (method != "GET") {
                        writeResponse(
                            client,
                            "405 Method Not Allowed",
                            "text/plain",
                            "Method not allowed".toByteArray(),
                            keepAlive = false,
                            extraHeaders = mapOf("Allow" to "GET", "Cache-Control" to "no-store"),
                        )
                        break
                    }

                    val tileResponse = resolveTile(path)
                    val status: String
                    val contentType: String
                    val body: ByteArray
                    val headers: Map<String, String>
                    if (tileResponse == null) {
                        status = "404 Not Found"
                        contentType = "text/plain"
                        body = "Not found".toByteArray()
                        headers = cacheHeaders(NO_STORE_CACHE_CONTROL)
                    } else {
                        status = "200 OK"
                        contentType = "image/png"
                        body = tileResponse.body
                        headers = cacheHeaders(tileResponse.cacheControl)
                    }

                    val ok =
                        writeResponse(
                            client,
                            status,
                            contentType,
                            body,
                            keepAlive = keepAlive,
                            extraHeaders = headers,
                        )

                    val tookMs = (System.nanoTime() - startNs) / 1_000_000
                    if (!ok) {
                        Log.w(TAG, "Write failed (client canceled?) status=$status path=${request.path}")
                        break
                    }
                    if (tookMs >= SLOW_RESPONSE_WARN_MS) {
                        val key = parseTileKey(path)
                        if (key != null) {
                            Log.w(
                                TAG,
                                "Slow tile response took=${tookMs}ms route=${key.routeId} " +
                                    "tileSize=${key.tileSize} z=${key.z} x=${key.x} y=${key.y} status=$status",
                            )
                        } else {
                            Log.w(TAG, "Slow response took=${tookMs}ms status=$status path=${request.path}")
                        }
                    }

                    handled += 1
                    if (!keepAlive) {
                        break
                    }
                }
            } catch (_: Exception) {
                // Ignore per-connection errors to avoid crashing the server.
            }
        }
    }

    private fun readRequest(reader: BufferedReader): Request? {
        var requestLine: String? = null

        // prevent too big header
        var headerLineCnt = 30
        while (requestLine == null && headerLineCnt > 0) {
            val line = readLineBounded(reader) ?: return null
            if (line.isNotEmpty()) {
                requestLine = line
            }
            headerLineCnt--
        }
        if (requestLine == null) return null

        val parts = requestLine.split(" ")
        val valid = parts.size >= 2
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1) ?: ""
        val httpVersion = parts.getOrNull(2) ?: "HTTP/1.0"

        val headers = HashMap<String, String>()
        var remainingHeaders = MAX_HEADER_COUNT
        while (remainingHeaders > 0) {
            remainingHeaders--
            val line = readLineBounded(reader) ?: break
            if (line.isEmpty()) {
                break
            }
            val index = line.indexOf(':')
            if (index <= 0) continue
            val key = line.substring(0, index).trim().lowercase()
            val value = line.substring(index + 1).trim()
            headers[key] = value
        }

        val req =
            Request(
                method = method,
                path = path,
                httpVersion = httpVersion,
                headers = headers,
                valid = valid,
            )
        return req
    }

    /**
     * Reads one CRLF/LF-terminated line, giving up (null) if it exceeds
     * [MAX_LINE_LENGTH] — unlike BufferedReader.readLine(), which buffers an
     * arbitrarily long line in memory.
     */
    private fun readLineBounded(reader: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            when (ch.toChar()) {
                '\n' -> return sb.toString()
                '\r' -> {
                    // Swallow the CR; the LF (if any) terminates on the next read.
                }
                else -> {
                    if (sb.length >= MAX_LINE_LENGTH) return null
                    sb.append(ch.toChar())
                }
            }
        }
    }

    private fun shouldKeepAlive(request: Request): Boolean {
        val connection = request.headers["connection"]?.lowercase()
        return when (request.httpVersion) {
            "HTTP/1.1" -> connection != "close"
            "HTTP/1.0" -> connection == "keep-alive"
            else -> false
        }
    }

    private fun resolveTile(path: String): TileResponse? {
        val key = parseTileKey(path) ?: return null
        val routeId = key.routeId
        val z = key.z
        val x = key.x
        val y = key.y

        if (loggedRoutes.add(routeId)) {
            Log.d("LocalTileServer", "First tile request route=$routeId tileSize=${key.tileSize} z=$z x=$x y=$y")
        }

        val provider = providers[routeId] ?: return null
        val bytes = provider.renderTile(TileRequest(x = x, y = y, z = z)) ?: return null
        val cacheControl =
            if (forceNoStoreCache) {
                NO_STORE_CACHE_CONTROL
            } else {
                LONG_CACHE_CONTROL
            }
        return TileResponse(bytes, cacheControl)
    }

    private fun parseTileKey(path: String): TileKey? {
        if (path.isEmpty()) return null
        val segments = path.split("/").filter { it.isNotEmpty() }
        if (segments.size < 6 || segments[0] != "tiles") return null
        val routeId = segments[1]
        val tileSize = segments[2].toIntOrNull() ?: return null
        val withCacheKey = segments.size >= 7
        val zIndex = if (withCacheKey) 4 else 3
        val xIndex = if (withCacheKey) 5 else 4
        val yIndex = if (withCacheKey) 6 else 5
        val z = segments.getOrNull(zIndex)?.toIntOrNull() ?: return null
        val x = segments.getOrNull(xIndex)?.toIntOrNull() ?: return null
        val y = segments.getOrNull(yIndex)?.substringBefore('.')?.toIntOrNull() ?: return null
        return TileKey(routeId = routeId, tileSize = tileSize, z = z, x = x, y = y)
    }

    private fun cacheHeaders(cacheControl: String): Map<String, String> =
        mapOf(
            "Cache-Control" to cacheControl,
            "Pragma" to "no-cache",
            "Expires" to "0",
        )

    private fun writeResponse(
        client: Socket,
        status: String,
        contentType: String,
        body: ByteArray,
        keepAlive: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Boolean {
        try {
            val output = client.getOutputStream()
            val headers = StringBuilder()
            headers.append("HTTP/1.1 ").append(status).append("\r\n")
            headers.append("Content-Type: ").append(contentType).append("\r\n")
            headers.append("Content-Length: ").append(body.size).append("\r\n")
            headers.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
            for ((key, value) in extraHeaders) {
                headers
                    .append(key)
                    .append(": ")
                    .append(value)
                    .append("\r\n")
            }
            headers.append("\r\n")
            output.write(headers.toString().toByteArray())
            output.write(body)
            output.flush()
            return true
        } catch (_: Exception) {
            // Client closed the connection early or canceled; ignore.
            return false
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val httpVersion: String,
        val headers: Map<String, String>,
        val valid: Boolean,
    )

    private data class TileResponse(
        val body: ByteArray,
        val cacheControl: String,
    )

    private data class TileKey(
        val routeId: String,
        val tileSize: Int,
        val z: Int,
        val x: Int,
        val y: Int,
    )

    companion object {
        private const val TAG = "LocalTileServer"
        private const val MAX_KEEP_ALIVE_REQUESTS = 200
        private const val MAX_HEADER_COUNT = 64
        private const val MAX_LINE_LENGTH = 8192
        // Tile rendering is CPU-bound (canvas + PNG encode), so more workers than
        // cores adds contention, not throughput. The deep queue absorbs request
        // bursts (e.g. ArcGIS fetching several LODs during a zoom animation)
        // without shedding connections.
        private const val MAX_WORKER_THREADS = 8
        private const val MAX_QUEUED_CONNECTIONS = 256
        private const val WORKER_KEEP_ALIVE_SECONDS = 60L
        private const val LONG_CACHE_CONTROL = "public, max-age=31536000, immutable"
        private const val NO_STORE_CACHE_CONTROL = "no-store, no-cache, must-revalidate, max-age=0"
        private const val SLOW_RESPONSE_WARN_MS = 250L
        private const val HTTP_PORT = 0
        private const val HTTP_SERVER_BACKLOG = 100

        fun startServer(forceNoStoreCache: Boolean = false): LocalTileServer {
            // Bind to the wildcard address (dual-stack) so clients can reach the
            // server via either 127.0.0.1 or ::1 — binding to a single loopback
            // address breaks HTTP stacks that resolve localhost to the other IP
            // family (e.g. Mapbox). Device-internal access is enforced in
            // acceptLoop by rejecting non-loopback source addresses.
            val socket = ServerSocket(HTTP_PORT, HTTP_SERVER_BACKLOG)
            val server = LocalTileServer(socket, forceNoStoreCache = forceNoStoreCache)
            server.start()
            return server
        }
    }
}
