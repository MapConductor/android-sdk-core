package com.mapconductor.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * URL の画像を、読み終えるまでは透明に、読み終えたらその画像を描く Drawable。
 * [com.mapconductor.core.marker.ImageIcon] が `imageUrl` で作られたときの中身。
 *
 * 画像は一度届いたら手放さない（[RemoteImageLoader] のキャッシュから追い出されても描ける）。
 * 透明から画像への変化は一方向で、一度しか起きない。
 */
@InternalMapConductorApi
internal class RemoteImageDrawable(
    val url: String,
) : Drawable() {
    private val lock = Any()

    @Volatile
    private var bitmap: Bitmap? = RemoteImageLoader.cached(url)
    private var settled = bitmap != null
    private var listeners: MutableList<() -> Unit>? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        if (!settled) RemoteImageLoader.load(url, ::onLoaded)
    }

    /** 画像が届いているか。 */
    val isReady: Boolean
        get() = bitmap != null

    /** URL と、届いているかどうか。届いた時点で一度だけ変わる。 */
    fun identity(): Int = 31 * url.hashCode() + if (isReady) 1 else 0

    /**
     * 読み込みが終わったら（失敗も含めて）[listener] を一度だけ呼ぶ。
     * もう終わっていれば呼ばずに false を返す。呼ぶのは読み込みのスレッド。
     */
    fun whenLoaded(listener: () -> Unit): Boolean =
        synchronized(lock) {
            if (settled) return false
            val list = listeners ?: mutableListOf<() -> Unit>().also { listeners = it }
            list.add(listener)
            true
        }

    private fun onLoaded(result: Bitmap?) {
        val toCall =
            synchronized(lock) {
                bitmap = result
                settled = true
                listeners.also { listeners = null }
            }
        toCall?.forEach { it() }
    }

    override fun draw(canvas: Canvas) {
        val image = bitmap ?: return
        canvas.drawBitmap(image, null, bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = bitmap?.width ?: -1

    override fun getIntrinsicHeight(): Int = bitmap?.height ?: -1
}

/**
 * URL から読んだ画像を包む [BitmapDrawable]。
 * [com.mapconductor.core.groundimage.GroundImageState] が `imageUrl` で作られたときの `image`。
 *
 * 同じ URL の同じ画像なら等しい。毎回作り直す `GroundImage(imageUrl = …)` でも
 * 指紋が変わらず、作り直しのたびに描き直さずに済む。読み終えるまでは 1 px の透明な画像。
 */
@InternalMapConductorApi
internal class UrlImageDrawable(
    val url: String,
    bitmap: Bitmap?,
) : BitmapDrawable(null, bitmap ?: RemoteImageLoader.transparentPixel) {
    override fun equals(other: Any?): Boolean = other is UrlImageDrawable && other.url == url && other.bitmap === bitmap

    override fun hashCode(): Int = 31 * url.hashCode() + System.identityHashCode(bitmap)
}
