package com.mapconductor.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * URL で渡された画像（[com.mapconductor.core.marker.ImageIcon] の `imageUrl`、
 * [com.mapconductor.core.groundimage.GroundImageState] の `imageUrl`）を読む。
 *
 * - 同じ URL は同時に一度だけ取りに行き、待っている全員に同じ [Bitmap] を渡す。
 *   14 万本のマーカーが 1 つのアイコンを共有していても、通信は 1 回で済む。
 * - 読んだ画像は大きさの合計で [MAX_CACHE_BYTES] まで覚えておく。
 * - 失敗は [RETRY_AFTER_MS] のあいだ覚えておき、その間の要求にはすぐ null を返す。
 *   ログは URL ごとに一度だけ出す。
 * - 読めるのは http / https / file / data。http(s) にはアプリの `android.permission.INTERNET` が要る。
 *
 * 読み込みの完了は、呼び出し元とは別のスレッドから通知する。
 */
@InternalMapConductorApi
internal object RemoteImageLoader {
    private const val TAG = "MapConductor"
    private const val MAX_CACHE_BYTES = 32 * 1024 * 1024
    private const val RETRY_AFTER_MS = 30_000L
    private const val MAX_REDIRECTS = 10

    /** URL からバイト列を取る。テストで差し替える。 */
    @Volatile
    internal var fetch: (url: String) -> ByteArray = ::defaultFetch

    /** 取得と復号を走らせる先。テストで差し替える。 */
    @Volatile
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    private val scope = CoroutineScope(SupervisorJob())
    private val lock = Any()
    private val cache =
        object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val failedAt = HashMap<String, Long>()
    private val reported = HashSet<String>()

    /** 読み終えるまでの代わりに使う、1 px の透明な画像。 */
    val transparentPixel: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
    }

    /** 読み終えて覚えている画像。無ければ null（読み込みは始めない）。 */
    fun cached(url: String): Bitmap? = synchronized(lock) { cache.get(url) }

    /**
     * [url] の画像を読み、終わったら [onDone] を一度だけ呼ぶ（失敗は null）。
     *
     * 覚えている画像や、最近失敗した URL なら、その場で呼ぶ。
     */
    fun load(
        url: String,
        onDone: (Bitmap?) -> Unit,
    ) {
        val settled: Bitmap?
        synchronized(lock) {
            val hit = cache.get(url)
            val failed = failedAt[url]
            when {
                hit != null -> settled = hit
                failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS -> settled = null
                else -> {
                    val listeners = waiting[url]
                    if (listeners != null) {
                        listeners.add(onDone)
                    } else {
                        waiting[url] = mutableListOf(onDone)
                        start(url)
                    }
                    return
                }
            }
        }
        onDone(settled)
    }

    private fun start(url: String) {
        scope.launch(dispatcher) {
            val result =
                runCatching {
                    decode(fetch(url)) ?: throw IllegalArgumentException("not an image")
                }
            val bitmap = result.getOrNull()
            val listeners =
                synchronized(lock) {
                    if (bitmap != null) {
                        cache.put(url, bitmap)
                        failedAt.remove(url)
                    } else {
                        failedAt[url] = System.currentTimeMillis()
                        if (reported.add(url)) {
                            Log.w(TAG, "Failed to load image: $url", result.exceptionOrNull())
                        }
                    }
                    waiting.remove(url).orEmpty()
                }
            listeners.forEach { it(bitmap) }
        }
    }

    /** 覚えている画像と失敗を忘れ、差し替えた取得手段を元に戻す。テスト用。 */
    internal fun resetForTest() {
        synchronized(lock) {
            cache.evictAll()
            failedAt.clear()
            reported.clear()
        }
        fetch = ::defaultFetch
        dispatcher = Dispatchers.IO
    }

    /**
     * 画素の大きさのまま復号する。密度は [Bitmap.DENSITY_NONE] にして、`BitmapDrawable` に
     * 包んでも端末の密度で縮まないようにする。
     */
    private fun decode(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inScaled = false }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.apply {
            density = Bitmap.DENSITY_NONE
        }
    }

    private fun defaultFetch(url: String): ByteArray =
        when {
            url.startsWith("data:") -> decodeDataUrl(url)
            url.startsWith("file://") -> File(URL(url).path).readBytes()
            url.startsWith("http://") || url.startsWith("https://") -> fetchHttp(url)
            else -> throw IllegalArgumentException("Unsupported image URL: $url")
        }

    private fun decodeDataUrl(url: String): ByteArray {
        val comma = url.indexOf(',')
        require(comma >= 0) { "Malformed data URL" }
        val header = url.substring(0, comma)
        val body = url.substring(comma + 1)
        return if (header.endsWith(";base64")) {
            Base64.decode(body, Base64.DEFAULT)
        } else {
            URLDecoder.decode(body, "UTF-8").toByteArray()
        }
    }

    /**
     * リダイレクトは手で追う（HttpURLConnection の自動追従は http と https をまたがない）。
     * android-kml の `KMLLoader` と同じ。
     */
    private fun fetchHttp(urlStr: String): ByteArray {
        var url = URL(urlStr)
        repeat(MAX_REDIRECTS) {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return connection.inputStream.use { it.readBytes() }
                    in 300..399 -> {
                        val location =
                            connection.getHeaderField("Location")
                                ?: throw IllegalStateException("HTTP $status without Location: $url")
                        url = URL(url, location)
                    }
                    else -> throw IllegalStateException("HTTP $status: $url")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects: $urlStr")
    }
}
