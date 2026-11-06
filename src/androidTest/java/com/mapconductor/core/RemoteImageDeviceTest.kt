package com.mapconductor.core

import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.marker.ImageIcon
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.iconRevision
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * URL で渡す画像（`ImageIcon(imageUrl)` / `GroundImageState(imageUrl)`）。
 *
 * 取得は差し替えた [RemoteImageLoader.fetch] がラッチで止めるので、「読み込み中」と
 * 「届いた後」を順に確かめられる。復号は端末の BitmapFactory を使う。
 */
@RunWith(AndroidJUnit4::class)
class RemoteImageDeviceTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val release = CountDownLatch(1)
    private val fetches = AtomicInteger()
    private var failing = false

    private val png: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        RemoteImageLoader.resetForTest()
        RemoteImageLoader.dispatcher = executor.asCoroutineDispatcher()
        RemoteImageLoader.fetch = { url ->
            fetches.incrementAndGet()
            release.await(5, TimeUnit.SECONDS)
            if (failing) throw IllegalStateException("offline: $url")
            png
        }
    }

    @After
    fun tearDown() {
        RemoteImageLoader.resetForTest()
        executor.shutdownNow()
    }

    /** 取得のスレッドで、それまでに積まれた仕事が終わるのを待つ。 */
    private fun drain() {
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun loadAll() {
        release.countDown()
        drain()
    }

    @Test
    fun oneFetchPerUrlAndEveryoneGetsTheSameImage() {
        val results = mutableListOf<Bitmap?>()
        RemoteImageLoader.load("https://example.test/a.png") { synchronized(results) { results.add(it) } }
        RemoteImageLoader.load("https://example.test/a.png") { synchronized(results) { results.add(it) } }
        loadAll()

        assertEquals(1, fetches.get())
        assertEquals(2, results.size)
        assertSame(results[0], results[1])
        assertEquals(8, results[0]!!.width)
        // 読み終えた後は、その場で返る。
        var immediate: Bitmap? = null
        RemoteImageLoader.load("https://example.test/a.png") { immediate = it }
        assertSame(results[0], immediate)
        assertEquals(1, fetches.get())
    }

    @Test
    fun aFailedUrlStaysFailed() {
        failing = true
        var first: Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        RemoteImageLoader.load("https://example.test/missing.png") { first = it }
        loadAll()
        assertNull(first)

        var second: Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        RemoteImageLoader.load("https://example.test/missing.png") { second = it }
        assertNull(second)
        assertEquals(1, fetches.get())
    }

    @Test
    fun dataUrlsAreDecodedWithoutTheNetwork() {
        RemoteImageLoader.resetForTest()
        val url = "data:image/png;base64," + android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
        val done = CountDownLatch(1)
        var bitmap: Bitmap? = null
        RemoteImageLoader.load(url) {
            bitmap = it
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(8, bitmap!!.width)
    }

    @Test
    fun iconLooksTheSameSizeAndChangesOnceWhenTheImageArrives() {
        val icon = ImageIcon(imageUrl = "https://example.test/icon.png", iconSize = Dp(16f))
        val same = ImageIcon(imageUrl = "https://example.test/icon.png", iconSize = Dp(16f))
        assertEquals("https://example.test/icon.png", icon.imageUrl)
        assertEquals(icon, same)
        val pendingHash = icon.hashCode()
        val pending = icon.toBitmapIcon()
        val center = pending.bitmap.width / 2
        assertEquals(0, Color.alpha(pending.bitmap.getPixel(center, center)))

        loadAll()

        assertNotEquals(pendingHash, icon.hashCode())
        assertEquals(icon, same)
        val ready = icon.toBitmapIcon()
        assertEquals(pending.size, ready.size)
        assertEquals(pending.anchor, ready.anchor)
        assertEquals(Color.RED, ready.bitmap.getPixel(center, center))
        // copy は URL を引き継ぐ。
        assertEquals("https://example.test/icon.png", icon.copy(scale = 2f).imageUrl)
        assertNull(ImageIcon(image = ColorDrawable(Color.RED)).imageUrl)
    }

    @Test
    fun markerRedrawsWhenItsIconArrivesButNotAfterTheIconWasReplaced() {
        val icon = ImageIcon(imageUrl = "https://example.test/marker.png")
        val kept = MarkerState(position = GeoPoint.fromLatLong(35.0, 139.0), icon = icon)
        val replaced = MarkerState(position = GeoPoint.fromLatLong(35.0, 139.0), icon = icon)
        val keptWrites = AtomicInteger()
        val replacedWrites = AtomicInteger()
        kept.mutations.listen { keptWrites.incrementAndGet() }
        replaced.icon = ImageIcon(image = ColorDrawable(Color.BLUE))
        replaced.mutations.listen { replacedWrites.incrementAndGet() }
        val before = kept.fingerPrint()

        loadAll()

        assertEquals(1, keptWrites.get())
        assertEquals(0, replacedWrites.get())
        assertEquals(1, kept.iconRevision)
        assertEquals(0, replaced.iconRevision)
        assertNotEquals(before, kept.fingerPrint())
    }

    @Test
    fun groundImageShowsTheImageOnceLoadedAndKeepsOnlyTheLatestUrl() {
        val bounds =
            GeoRectBounds(
                southWest = GeoPoint.fromLatLong(35.0, 139.0),
                northEast = GeoPoint.fromLatLong(35.1, 139.1),
            )
        val state = GroundImageState(bounds = bounds, imageUrl = "https://example.test/ground.png")
        val placeholder = state.image
        assertEquals(1, placeholder.intrinsicWidth)

        // 読み終える前に別の URL へ替えたら、前の結果は使わない。
        val other = GroundImageState(bounds = bounds, imageUrl = "https://example.test/old.png")
        other.imageUrl = "https://example.test/new.png"

        loadAll()

        assertEquals(8, state.image.intrinsicWidth)
        assertNotEquals(placeholder.hashCode(), state.image.hashCode())
        assertEquals("https://example.test/new.png", other.imageUrl)
        assertEquals(
            GroundImageState(bounds = bounds, imageUrl = "https://example.test/new.png").image,
            other.image,
        )

        // 画像を直接書き換えたら URL は消える。copy は URL を引き継ぐ。
        assertEquals("https://example.test/ground.png", state.copy(opacity = 0.5f).imageUrl)
        state.image = ColorDrawable(Color.GREEN)
        assertNull(state.imageUrl)
        assertFalse(state.image is UrlImageDrawable)
    }
}
