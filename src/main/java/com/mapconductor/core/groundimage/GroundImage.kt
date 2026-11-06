package com.mapconductor.core.groundimage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.mapconductor.core.ComponentState
import com.mapconductor.core.RemoteImageLoader
import com.mapconductor.core.StateMutationSignal
import com.mapconductor.core.UrlImageDrawable
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import java.io.Serializable
import android.graphics.drawable.Drawable

/**
 * 地図に貼る画像。
 *
 * 画像は `Drawable` で渡すか、[imageUrl]（http / https / file / data）で渡す。URL のときは
 * SDK が読み、読み終えるまでは透明、読み終えたら [image] がその画像に替わって描き直される。
 * http(s) にはアプリの `android.permission.INTERNET` が要る。react-sdk の `imageUrl` と同じ。
 */
class GroundImageState(
    bounds: GeoRectBounds,
    image: Drawable,
    opacity: Float = 1.0f,
    tileSize: Int = GroundImageTileProvider.DEFAULT_TILE_SIZE,
    clickable: Boolean = true,
    id: String? = null,
    extra: Serializable? = null,
    onClick: OnGroundImageEventHandler? = null,
) : ComponentState {
    override val id = (id ?: generateId(bounds, image, opacity, tileSize, clickable, extra)).toString()

    /**
     * Writes to the fields below are announced here rather than discovered by
     * reading them all back. See [StateMutationSignal].
     */
    override val mutations = StateMutationSignal()

//    var bounds by StateFlowDelegate(bounds)
    var bounds by mutations.notifying(bounds)
    private val currentImage = mutations.notifying(image)
    private val currentImageUrl = mutations.notifying<String?>(null)

    /** 描く画像。書き換えると [imageUrl] は null になる。 */
    var image: Drawable
        get() = currentImage.value
        set(value) {
            currentImageUrl.value = null
            currentImage.value = value
        }

    /**
     * 画像の URL。`Drawable` で作ったか、[image] を書き換えたなら null。
     *
     * 書き換えると、読み終えるまで [image] は透明になり、読み終えたらその画像になる。
     * 読めなかったときは透明のまま。読み終える前にまた書き換えたら、古い方の結果は捨てる。
     */
    var imageUrl: String?
        get() = currentImageUrl.value
        set(value) {
            currentImageUrl.value = value
            if (value == null) return
            currentImage.value = UrlImageDrawable(value, null)
            RemoteImageLoader.load(value) { bitmap ->
                if (bitmap != null && currentImageUrl.value == value) {
                    currentImage.value = UrlImageDrawable(value, bitmap)
                }
            }
        }
    var opacity by mutations.notifying(opacity)

    var tileSize by mutations.notifying(tileSize)

    /**
     * タップを受け取るか。`false` ならこのグラウンドイメージはタップに対して透過し、
     * 下のオーバーレイ（無ければ地図クリック）へイベントが流れる。
     *
     * 判定はコアの [com.mapconductor.core.groundimage.GroundImageManager.find] が
     * 行うため、どのプロバイダでも同じ挙動になる。描画には影響しないので
     * [fingerPrint] には含めない（含めると値を変えるたびにタイルが作り直される）。
     */
    var clickable by mutations.notifying(clickable)
    var extra by mutations.notifying(extra)
    var onClick by mutations.notifying(onClick)

    /** [imageUrl] の画像を貼る。読み終えるまでは透明。ほかの引数は `Drawable` を渡すコンストラクタと同じ。 */
    constructor(
        bounds: GeoRectBounds,
        imageUrl: String,
        opacity: Float = 1.0f,
        tileSize: Int = GroundImageTileProvider.DEFAULT_TILE_SIZE,
        clickable: Boolean = true,
        id: String? = null,
        extra: Serializable? = null,
        onClick: OnGroundImageEventHandler? = null,
    ) : this(
        bounds = bounds,
        image = UrlImageDrawable(imageUrl, null),
        opacity = opacity,
        tileSize = tileSize,
        clickable = clickable,
        id = id,
        extra = extra,
        onClick = onClick,
    ) {
        this.imageUrl = imageUrl
    }

    fun fingerPrint(): GroundImageFingerPrint =
        GroundImageFingerPrint(
            id = id.hashCode(),
            bounds = bounds.hashCode(),
            image = image.hashCode(),
            opacity = opacity.hashCode(),
            tileSize = tileSize.hashCode(),
            extra = extra?.hashCode() ?: 0,
        )

    private fun generateId(
        bounds: GeoRectBounds,
        image: Drawable,
        opacity: Float,
        tileSize: Int,
        clickable: Boolean,
        extra: Serializable?,
    ): Int {
        var result = bounds.hashCode()
        result = 31 * result + image.hashCode()
        result = 31 * result + opacity.hashCode()
        result = 31 * result + tileSize.hashCode()
        result = 31 * result + clickable.hashCode()
        result = 31 * result + (extra?.hashCode() ?: 0)
        return result
    }

    // ios-sdk / react-sdk の GroundImageState.copy() と揃えるための copy。
    fun copy(
        bounds: GeoRectBounds = this.bounds,
        image: Drawable = this.image,
        opacity: Float = this.opacity,
        tileSize: Int = this.tileSize,
        clickable: Boolean = this.clickable,
        id: String? = this.id,
        extra: Serializable? = this.extra,
        onClick: OnGroundImageEventHandler? = this.onClick,
    ): GroundImageState {
        // 画像を差し替えないなら URL ごと引き継ぐ（読み込み中でも、届いたら写る）。
        val url = imageUrl
        if (url != null && image === this.image) {
            return GroundImageState(
                bounds = bounds,
                imageUrl = url,
                opacity = opacity,
                tileSize = tileSize,
                clickable = clickable,
                id = id,
                extra = extra,
                onClick = onClick,
            )
        }
        return GroundImageState(
            bounds = bounds,
            image = image,
            opacity = opacity,
            tileSize = tileSize,
            clickable = clickable,
            id = id,
            extra = extra,
            onClick = onClick,
        )
    }

    override fun equals(other: Any?): Boolean = (other as? GroundImageState)?.hashCode() == this.hashCode()

    override fun hashCode(): Int = fingerPrint().hashCode()
}

data class GroundImageFingerPrint(
    val id: Int,
    val bounds: Int,
    val image: Int,
    val opacity: Int,
    val tileSize: Int,
    val extra: Int,
)

class GroundImageEvent(
    val state: GroundImageState,
    clicked: GeoPoint?,
) {
    // 生成時に wrap して [-180,180] / [-90,90] に正規化する（日付変更線対策）。
    // 正規化をここ（イベント型＝出口）に一元化することで、どの配送経路でも wrap 漏れが起きない。
    val clicked: GeoPoint? = clicked?.let { GeoPoint.from(it.wrap()) }
}

typealias OnGroundImageEventHandler = (GroundImageEvent) -> Unit
