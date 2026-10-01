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

    /**
     * ルートごとの「このレベルは透明でよい」判定。
     *
     * ArcGIS の 3D ビューは画面のレベルより上の全レベルも要求し、画面のレベルが
     * 載れば一枚も描かない。要求ごとのフックを持たない Android の
     * `WebTiledLayer` では、ここで provider を呼ぶ前に透明を返すのが唯一の手。
     * 透明は `no-store` で返す。ArcGIS 側のキャッシュは別で、それは呼び手が
     * レイヤーを作り直して捨てる。
     */
    private val levelGates = ConcurrentHashMap<String, (Int) -> Boolean>()

    /**
     * Whole documents served by id, beside the tiles: a style JSON that a
     * vector-capable map is to read directly, for one. Served `no-store` --
     * the id changes when the content does, so there is nothing to revalidate,
     * and a map holding on to a stale document is harder to notice than one
     * refetching a small one.
     */
    private val documents = ConcurrentHashMap<String, Document>()

    /**
     * Directories served by route, for a map that reads its style's tiles,
     * glyphs and sprite straight from the device: an offline package. A path
     * that is not in the directory is offered to the route's fallback, which
     * is how "online" and "offline" differ for the same package -- online,
     * the fallback fetches upstream; offline, there is none, and the map is
     * told there is no such tile.
     */
    private val fileRoutes = ConcurrentHashMap<String, FileRoute>()

    /** Requests dropped because the map had stopped waiting for them. */
    private val abandoned = AtomicLong()
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
        levelGates.remove(routeId)
    }

    /**
     * [gate] が true を返すレベルの要求は、provider を呼ばずに透明タイルで答える。
     * null で解除。
     */
    fun setLevelGate(
        routeId: String,
        gate: ((level: Int) -> Boolean)?,
    ) {
        if (gate == null) levelGates.remove(routeId) else levelGates[routeId] = gate
    }

    /**
     * Serves [body] at [documentUrl] until [unregisterDocument].
     *
     * [id] must be URL-safe as given; it is matched against the request path
     * verbatim.
     */
    fun registerDocument(
        id: String,
        contentType: String,
        body: ByteArray,
    ) {
        documents[id] = Document(contentType, body)
    }

    fun unregisterDocument(id: String) {
        documents.remove(id)
    }

    fun documentUrl(id: String): String = "$baseUrl/$DOCS_PREFIX/$id"

    /**
     * Serves the files under [directory] at [filesUrl]`/<relative path>`.
     *
     * The content type follows the extension (`.mvt`, `.pbf`, `.json`,
     * `.png`); a file is served as immutable, since a package does not change
     * under a map. A path with no file is answered by [fallback] -- served
     * `no-store`, so what came from upstream is not mistaken for the package
     * -- or, when that is null or answers null, with 404, which the map takes
     * as "no such tile" and does not retry.
     */
    fun registerFiles(
        routeId: String,
        directory: java.io.File,
        fallback: ((relativePath: String) -> ByteArray?)? = null,
    ) {
        fileRoutes[routeId] = FileRoute(directory, fallback)
    }

    fun unregisterFiles(routeId: String) {
        fileRoutes.remove(routeId)
    }

    fun filesUrl(routeId: String): String = "$baseUrl/$FILES_PREFIX/$routeId"

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
     * Draws the tile a local URL names, in this process, without the HTTP hop.
     *
     * A map SDK that fetches through its own network stack may refuse to
     * while the device reports no connectivity -- HERE will not even ask
     * 127.0.0.1 -- so its layer hands the request here instead. Same
     * providers, same answers: the bytes of a drawn tile, a transparent tile
     * where the provider has nothing, and null when the route is unknown or
     * the render failed, which the caller reports as a failure the map may
     * retry. The URL is one this server's own templates produced.
     */
    fun renderLocalTile(
        url: String,
        isCancelled: () -> Boolean = { false },
    ): ByteArray? {
        if (!url.startsWith("$baseUrl/")) return null
        val path = url.removePrefix("$baseUrl/").substringBefore('?')
        return when (val outcome = resolveTile(path, isCancelled)) {
            is TileOutcome.Tile -> outcome.body
            is TileOutcome.Empty -> TransparentTilePng.bytes(outcome.pixelSize)
            TileOutcome.NotFound, TileOutcome.Failed -> null
        }
    }

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
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "accepted a connection; workers busy=${clientExecutor.activeCount} " +
                            "queued=${clientExecutor.queue.size}",
                    )
                }
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
                client.soTimeout = SOCKET_TIMEOUT_MS
                // Unbuffered on purpose: the liveness check reads a byte from
                // here, and a buffer between the two would hide it.
                val peek = java.io.PushbackInputStream(client.getInputStream(), 1)
                val reader = BufferedReader(InputStreamReader(peek))
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
                    Log.d("Server", "---->$path")

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

                    if (path.startsWith("$DOCS_PREFIX/")) {
                        val document = documents[path.removePrefix("$DOCS_PREFIX/")]
                        val ok =
                            if (document == null) {
                                writeResponse(
                                    client,
                                    "404 Not Found",
                                    "text/plain",
                                    "Not found".toByteArray(),
                                    keepAlive = keepAlive,
                                    extraHeaders = cacheHeaders(NO_STORE_CACHE_CONTROL),
                                )
                            } else {
                                writeResponse(
                                    client,
                                    "200 OK",
                                    document.contentType,
                                    document.body,
                                    keepAlive = keepAlive,
                                    extraHeaders = cacheHeaders(NO_STORE_CACHE_CONTROL),
                                )
                            }
                        if (!ok) break
                        handled += 1
                        if (!keepAlive) break
                        continue
                    }

                    if (path.startsWith("$FILES_PREFIX/")) {
                        val ok = serveFile(client, path.removePrefix("$FILES_PREFIX/"), keepAlive)
                        if (!ok) break
                        handled += 1
                        if (!keepAlive) break
                        continue
                    }

                    // The map may have moved on since this request was
                    // queued -- a pinch or a fling abandons whole screens of
                    // tiles -- and drawing one it no longer wants puts it in
                    // front of the tiles it does. Rendering is where the time
                    // goes, so the cheapest thing that helps is not starting.
                    if (clientGone(client, peek)) {
                        val total = abandoned.incrementAndGet()
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "Gone before we started: $path (total=$total)")
                        }
                        break
                    }

                    val outcome = resolveTile(path) { clientGone(client, peek) }
                    // A tile that was abandoned mid-render comes back from the
                    // provider looking empty, and the difference matters: an
                    // "empty" answer is a picture the map would cache. Nothing
                    // is answered at all -- the connection simply ends, which
                    // the map reads as a failure it may retry.
                    val fromProvider = outcome is TileOutcome.Empty || outcome is TileOutcome.Failed
                    if (fromProvider && clientGone(client, peek)) {
                        val total = abandoned.incrementAndGet()
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "Abandoned mid-render, no answer sent: $path (total=$total)")
                        }
                        break
                    }
                    val status: String
                    val contentType: String
                    val body: ByteArray
                    val headers: Map<String, String>
                    when (outcome) {
                        TileOutcome.NotFound -> {
                            status = "404 Not Found"
                            contentType = "text/plain"
                            body = "Not found".toByteArray()
                            headers = cacheHeaders(NO_STORE_CACHE_CONTROL)
                        }
                        TileOutcome.Failed -> {
                            // 503: the map will retry. If these repeat for the
                            // same path, the provider is the place to look.
                            status = "503 Service Unavailable"
                            contentType = "text/plain"
                            body = "Tile render failed".toByteArray()
                            headers = cacheHeaders(NO_STORE_CACHE_CONTROL) + ("Retry-After" to "1")
                        }
                        is TileOutcome.Empty -> {
                            status = "200 OK"
                            contentType = "image/png"
                            body = TransparentTilePng.bytes(outcome.pixelSize)
                            headers = cacheHeaders(outcome.cacheControl)
                        }
                        is TileOutcome.Tile -> {
                            status = "200 OK"
                            contentType = "image/png"
                            body = outcome.body
                            headers = cacheHeaders(outcome.cacheControl)
                        }
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

    /**
     * Whether the other end has gone away.
     *
     * There is no portable way to ask a socket whether the peer is still
     * there, so this asks the only question a socket does answer: is there
     * anything to read. A closed connection reads end-of-stream at once; a
     * live idle one times out, which is the answer we want.
     *
     * The first version sent a byte of urgent data instead, which fails on a
     * dead connection and is discarded by any client that does not ask for it.
     * That is true of every HTTP client, and it is still a byte pushed into a
     * live connection at a moment of its choosing -- a poor thing to do to a
     * stream we also have to parse. Reading cannot corrupt anything, and the
     * byte is pushed back for the parser when there is one.
     */
    private fun clientGone(
        client: Socket,
        peek: java.io.PushbackInputStream,
    ): Boolean {
        if (client.isClosed || !client.isConnected) return true
        val timeout = runCatching { client.soTimeout }.getOrDefault(SOCKET_TIMEOUT_MS)
        return try {
            client.soTimeout = 1
            val byte = peek.read()
            if (byte == -1) {
                true
            } else {
                // A pipelined request, which is the parser's business.
                peek.unread(byte)
                false
            }
        } catch (_: java.net.SocketTimeoutException) {
            false
        } catch (_: java.io.IOException) {
            true
        } finally {
            runCatching { client.soTimeout = timeout }
        }
    }

    private fun resolveTile(
        path: String,
        isCancelled: () -> Boolean,
    ): TileOutcome {
        val key = parseTileKey(path) ?: return TileOutcome.NotFound
        val routeId = key.routeId
        val z = key.z
        val x = key.x
        val y = key.y

        if (loggedRoutes.add(routeId)) {
            Log.d("LocalTileServer", "First tile request route=$routeId tileSize=${key.tileSize} z=$z x=$x y=$y")
        }

        val provider = providers[routeId] ?: return TileOutcome.NotFound
        levelGates[routeId]?.let { gate ->
            if (gate(z)) return TileOutcome.Empty(key.tileSize * key.pixelRatio, NO_STORE_CACHE_CONTROL)
        }
        val cacheControl =
            if (forceNoStoreCache) {
                NO_STORE_CACHE_CONTROL
            } else {
                LONG_CACHE_CONTROL
            }
        val bytes =
            try {
                provider.renderTile(
                    TileRequest(x = x, y = y, z = z, pixelRatio = key.pixelRatio),
                    isCancelled,
                )
            } catch (error: Exception) {
                Log.w(TAG, "Tile render failed route=$routeId z=$z x=$x y=$y", error)
                return TileOutcome.Failed
            } ?: return TileOutcome.Empty(key.tileSize * key.pixelRatio, cacheControl)
        return TileOutcome.Tile(bytes, cacheControl)
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
        val tileCoordinate = segments.getOrNull(yIndex)?.let(::parseTileCoordinate) ?: return null
        return TileKey(
            routeId = routeId,
            tileSize = tileSize,
            z = z,
            x = x,
            y = tileCoordinate.y,
            pixelRatio = tileCoordinate.pixelRatio,
        )
    }

    /** `<routeId>/<relative path>` under the files prefix. */
    private fun serveFile(
        client: Socket,
        routePath: String,
        keepAlive: Boolean,
    ): Boolean {
        val routeId = routePath.substringBefore('/')
        val relative =
            runCatching { java.net.URLDecoder.decode(routePath.substringAfter('/', ""), "UTF-8") }
                .getOrDefault("")
        val route = fileRoutes[routeId]
        // No escaping the directory, whatever the request says.
        val safe = relative.isNotEmpty() && relative.split('/').none { it == ".." || it.isEmpty() }
        val file = if (route != null && safe) java.io.File(route.directory, relative) else null
        if (file != null && file.isFile) {
            return writeResponse(
                client,
                "200 OK",
                contentTypeFor(relative),
                file.readBytes(),
                keepAlive = keepAlive,
                extraHeaders = cacheHeaders(LONG_CACHE_CONTROL),
            )
        }
        val fetched =
            if (route?.fallback != null && safe) {
                runCatching { route.fallback.invoke(relative) }
                    .onFailure { Log.w(TAG, "file fallback failed: $relative", it) }
                    .getOrNull()
            } else {
                null
            }
        return if (fetched != null) {
            writeResponse(
                client,
                "200 OK",
                contentTypeFor(relative),
                fetched,
                keepAlive = keepAlive,
                extraHeaders = cacheHeaders(NO_STORE_CACHE_CONTROL),
            )
        } else {
            writeResponse(
                client,
                "404 Not Found",
                "text/plain",
                "Not found".toByteArray(),
                keepAlive = keepAlive,
                extraHeaders = cacheHeaders(NO_STORE_CACHE_CONTROL),
            )
        }
    }

    private fun contentTypeFor(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "mvt" -> "application/vnd.mapbox-vector-tile"
            "pbf" -> "application/x-protobuf"
            "json" -> "application/json"
            "png" -> "image/png"
            else -> "application/octet-stream"
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
            // WebView ベースの地図 SDK（Longdo / MapTiler 等）は MapLibre GL JS の fetch でタイルを取得する。
            // ローカルサーバは https のページから見て別オリジンのため、CORS を許可しないと取得がブロックされる。
            headers.append("Access-Control-Allow-Origin: *\r\n")
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

    /**
     * What a request resolved to. The distinctions matter because map SDKs
     * remember the answers differently: 404 is taken as permanent, so it is
     * reserved for requests that name nothing — a bad path, an unregistered
     * route. A provider with nothing to draw gets a transparent picture (an
     * empty spot is a real answer, cacheable and later replaced when the data
     * version moves the URL), and a provider that threw gets a 503 the map
     * knows to retry.
     */
    private sealed interface TileOutcome {
        class Tile(
            val body: ByteArray,
            val cacheControl: String,
        ) : TileOutcome

        class Empty(
            val pixelSize: Int,
            val cacheControl: String,
        ) : TileOutcome

        object NotFound : TileOutcome

        object Failed : TileOutcome
    }

    private class Document(
        val contentType: String,
        val body: ByteArray,
    )

    private class FileRoute(
        val directory: java.io.File,
        val fallback: ((String) -> ByteArray?)?,
    )

    private data class TileKey(
        val routeId: String,
        val tileSize: Int,
        val z: Int,
        val x: Int,
        val y: Int,
        val pixelRatio: Int,
    )

    companion object {
        private const val TAG = "LocalTileServer"
        private const val DOCS_PREFIX = "docs"
        private const val FILES_PREFIX = "files"
        private const val MAX_KEEP_ALIVE_REQUESTS = 200
        private const val SOCKET_TIMEOUT_MS = 5000
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

internal data class TileCoordinate(
    val y: Int,
    val pixelRatio: Int,
)

internal fun parseTileCoordinate(fileName: String): TileCoordinate? {
    val match = TILE_FILE_NAME_PATTERN.matchEntire(fileName) ?: return null
    val y = match.groupValues[1].toIntOrNull() ?: return null
    val pixelRatio = match.groupValues[2].toIntOrNull() ?: 1
    if (pixelRatio !in 1..MAX_TILE_PIXEL_RATIO) return null
    return TileCoordinate(y = y, pixelRatio = pixelRatio)
}

private val TILE_FILE_NAME_PATTERN = Regex("""^(\d+)(?:@(\d+)x)?\.png$""")
private const val MAX_TILE_PIXEL_RATIO = 3
