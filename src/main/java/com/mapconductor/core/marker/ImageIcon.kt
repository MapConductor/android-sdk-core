package com.mapconductor.core.marker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import com.mapconductor.core.BitmapIconCache
import com.mapconductor.core.ResourceProvider
import com.mapconductor.settings.Settings
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/**
 * 画像をそのまま（大きさだけ合わせて）使うアイコン。既定のピンの形にはしない。
 *
 * ## ラベル
 *
 * [label] を渡すと画像に文字を添える。書き方と既定値は [ColorDefaultIcon] と同じで、
 * ios-sdk / react-sdk の `ImageIcon` とも揃えてある。
 *
 * - `left` / `right` は画像の外側に置き、縦は画像の上端・中間・下端に揃える。
 * - `center` 列の `top` / `bottom` は画像の上・下の外側に置く。
 * - `"center center"`（既定）は画像の中央に重ねる。
 *
 * [labelWritingMode] に `"vertical-rl"` を渡すと縦書きになる（[AbstractDefaultIcon.labelWritingMode]）。
 *
 * コンストラクタの `anchor` / `infoAnchor` は、ラベルの有無によらず**画像に対する割合**。
 * 公開している [anchor] / [infoAnchor] は、ラベルで広がった全体に対する割合へ直した値。
 *
 * タイル描画は本体の画像と文字を別々に使うので、ラベル付きのアイコンをいくつ作っても、
 * 本体の画像は元の画像と大きさごとに 1 枚で済む。
 */
class ImageIcon(
    image: Drawable,
    override val iconSize: Dp = Settings.Default.iconSize,
    override val scale: Float = 1.0f,
    anchor: Offset = Offset(0.5f, 0.5f),
    infoAnchor: Offset = Offset(0.5f, 0.5f),
    override val debug: Boolean = false,
    val label: String? = null,
    val labelTextColor: Color? = Color.Black,
    val labelTextSize: TextUnit = 18.sp,
    val labelTypeFace: Typeface = Typeface.DEFAULT,
    val labelStrokeColor: Color = Color.White,
    val labelPosition: String = AbstractDefaultIcon.DEFAULT_LABEL_POSITION,
    val labelWritingMode: String = AbstractDefaultIcon.DEFAULT_LABEL_WRITING_MODE,
) : AndroidDrawableIcon(
        drawable = image,
    ) {
    private val requestedAnchor = anchor
    private val requestedInfoAnchor = infoAnchor

    private class Layout(
        val canvasSize: Int,
        val result: IconLabelLayout.Result,
        val label: LabelTypesetter.Metrics?,
        val textSizePx: Float,
        val outlinePx: Float,
    )

    private val layout: Layout by lazy {
        val canvasSize = (ResourceProvider.dpToPxForBitmap(iconSize.value) * scale).toInt()
        val size = canvasSize.toFloat()
        val textSizePx = LabelTypesetter.textSizePx(labelTextSize)
        val outline = LabelTypesetter.outlinePx(scale)
        val metrics =
            label?.let { text ->
                LabelTypesetter.measure(
                    text = text,
                    textSizePx = textSizePx,
                    typeface = labelTypeFace,
                    outlinePx = outline,
                    vertical = LabelTypesetter.isVertical(labelWritingMode),
                )
            }
        Layout(
            canvasSize = canvasSize,
            result =
                IconLabelLayout.compute(
                    markerSize = canvasSize,
                    bodyLeft = 0f,
                    bodyRight = size,
                    bodyTop = 0f,
                    bodyBottom = size,
                    centerY = size / 2f,
                    sideCenterY = size / 2f,
                    label = metrics,
                    outlinePx = outline,
                    labelPosition = labelPosition,
                ),
            label = metrics,
            textSizePx = textSizePx,
            outlinePx = outline,
        )
    }

    override val anchor: Offset by lazy { toBitmapFraction(requestedAnchor) }

    override val infoAnchor: Offset by lazy { toBitmapFraction(requestedInfoAnchor) }

    /** ビットマップの幅と高さが、画像（iconSize × scale の正方形）の何倍か。 */
    internal val extentRatio: Size by lazy {
        val layout = layout
        Size(
            layout.result.bitmapWidth.toFloat() / layout.canvasSize,
            layout.result.bitmapHeight.toFloat() / layout.canvasSize,
        )
    }

    init {
        // ラベル付きは、はみ出し量を作った時点で記録する（[LabelledIconExtent]）。
        // JVM の単体テストでは Paint が空実装なので、測れなければ記録しない。
        if (label != null) {
            runCatching { layout }.getOrNull()?.let { layout ->
                val result = layout.result
                LabelledIconExtent.note(
                    width = result.bitmapWidth.toFloat(),
                    height = result.bitmapHeight.toFloat(),
                    anchorX = (result.markerOffsetX + requestedAnchor.x * layout.canvasSize) / result.bitmapWidth,
                    anchorY = (result.markerOffsetY + requestedAnchor.y * layout.canvasSize) / result.bitmapHeight,
                )
            }
        }
    }

    private fun toBitmapFraction(requested: Offset): Offset {
        val layout = layout
        val result = layout.result
        return Offset(
            (result.markerOffsetX + requested.x * layout.canvasSize) / result.bitmapWidth,
            (result.markerOffsetY + requested.y * layout.canvasSize) / result.bitmapHeight,
        )
    }

    private fun getDrawableIdentity(): Int =
        when (drawable) {
            is BitmapDrawable -> {
                val bmp = drawable.bitmap
                if (bmp == null || bmp.isRecycled) {
                    0
                } else {
                    // Fast identity: generationId changes when bitmap pixels change.
                    // Avoid hashing bitmap contents, which is extremely expensive and makes large
                    // marker sets slow to add.
                    var result = 17
                    result = 31 * result + bmp.width
                    result = 31 * result + bmp.height
                    result = 31 * result + bmp.generationId
                    result = 31 * result + System.identityHashCode(bmp)
                    result
                }
            }
            is ColorDrawable -> drawable.color
            is GradientDrawable -> System.identityHashCode(drawable.constantState ?: drawable)
            else -> System.identityHashCode(drawable.constantState ?: drawable)
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false
        other as ImageIcon
        return getDrawableIdentity() == other.getDrawableIdentity() &&
            iconSize == other.iconSize &&
            scale == other.scale &&
            requestedAnchor == other.requestedAnchor &&
            requestedInfoAnchor == other.requestedInfoAnchor &&
            debug == other.debug &&
            label == other.label &&
            labelTextColor == other.labelTextColor &&
            labelTextSize == other.labelTextSize &&
            labelTypeFace == other.labelTypeFace &&
            labelStrokeColor == other.labelStrokeColor &&
            labelPosition == other.labelPosition &&
            labelWritingMode == other.labelWritingMode
    }

    /** ラベルを除いた見た目の hash。本体の画像のキャッシュキーに使い、ラベル違いで共有する。 */
    private fun bodyHashCode(): Int {
        var result = getDrawableIdentity()
        result = 31 * result + iconSize.hashCode()
        result = 31 * result + scale.hashCode()
        result = 31 * result + requestedAnchor.hashCode()
        result = 31 * result + requestedInfoAnchor.hashCode()
        result = 31 * result + debug.hashCode()
        return result
    }

    override fun hashCode(): Int {
        var result = bodyHashCode()
        result = 31 * result + (label?.hashCode() ?: 0)
        result = 31 * result + (labelTextColor?.hashCode() ?: 0)
        result = 31 * result + labelTextSize.hashCode()
        result = 31 * result + labelTypeFace.hashCode()
        result = 31 * result + labelStrokeColor.hashCode()
        result = 31 * result + labelPosition.hashCode()
        result = 31 * result + labelWritingMode.hashCode()
        return result
    }

    internal val tileBody: BitmapIcon
        get() {
            val id = bodyHashCode()
            BitmapIconCache.get(id)?.let {
                return it
            }

            val scaledSize = layout.canvasSize

            val bitmap =
                this.toBitmap(
                    drawable = drawable,
                    width = scaledSize,
                    height = scaledSize,
                )
            // Set bitmap density based on override (e.g., 1.0 for MapLibre to prevent auto-scaling)
            ResourceProvider.getBitmapDensity().let { density ->
                bitmap.density = (density * android.util.DisplayMetrics.DENSITY_DEFAULT).toInt()
            }

            val result =
                BitmapIcon(
                    bitmap = bitmap,
                    anchor = requestedAnchor,
                    size = Size(scaledSize.toFloat(), scaledSize.toFloat()),
                )
            BitmapIconCache.put(id, result)
            return result
        }

    internal val tileLabel: MarkerTileLabel?
        get() {
            val text = label ?: return null
            val layout = layout
            val metrics = layout.label ?: return null
            val result = layout.result
            val anchorX = result.markerOffsetX + requestedAnchor.x * layout.canvasSize
            val anchorY = result.markerOffsetY + requestedAnchor.y * layout.canvasSize
            return MarkerTileLabel(
                text = text,
                textSizePx = layout.textSizePx,
                typeface = labelTypeFace,
                textColor = (labelTextColor ?: Color.Black).toArgb(),
                strokeColor = labelStrokeColor.toArgb(),
                outlinePx = layout.outlinePx,
                centerOffsetX = result.labelCenterX - anchorX,
                centerOffsetY = result.labelCenterY - anchorY,
                width = metrics.width,
                height = metrics.height,
                vertical = LabelTypesetter.isVertical(labelWritingMode),
            )
        }

    override fun toBitmapIcon(): BitmapIcon {
        val body = tileBody
        val text = label ?: return body
        val id = "image_icon_${hashCode()}".hashCode()
        BitmapIconCache.get(id)?.let {
            return it
        }

        val layout = layout
        val result = layout.result
        val bitmap = createBitmap(result.bitmapWidth, result.bitmapHeight)
        ResourceProvider.getBitmapDensity().let { density ->
            bitmap.density = (density * android.util.DisplayMetrics.DENSITY_DEFAULT).toInt()
        }
        val canvas = Canvas(bitmap)
        val left = Math.round(result.markerOffsetX)
        val top = Math.round(result.markerOffsetY)
        canvas.drawBitmap(
            body.bitmap,
            null,
            Rect(left, top, left + layout.canvasSize, top + layout.canvasSize),
            null,
        )
        LabelTypesetter.draw(
            canvas = canvas,
            text = text,
            centerX = result.labelCenterX,
            centerY = result.labelCenterY,
            textSizePx = layout.textSizePx,
            typeface = labelTypeFace,
            textColor = (labelTextColor ?: Color.Black).toArgb(),
            strokeColor = labelStrokeColor.toArgb(),
            outlinePx = layout.outlinePx,
            vertical = LabelTypesetter.isVertical(labelWritingMode),
            alignToPixel = false,
        )

        val icon =
            BitmapIcon(
                bitmap = bitmap,
                anchor = anchor,
                size = Size(result.bitmapWidth.toFloat(), result.bitmapHeight.toFloat()),
            )
        BitmapIconCache.put(id, icon)
        return icon
    }

    fun copy(
        image: Drawable = this.drawable,
        iconSize: Dp = this.iconSize,
        scale: Float = this.scale,
        anchor: Offset = this.requestedAnchor,
        infoAnchor: Offset = this.requestedInfoAnchor,
        debug: Boolean = this.debug,
        label: String? = this.label,
        labelTextColor: Color? = this.labelTextColor,
        labelTextSize: TextUnit = this.labelTextSize,
        labelTypeFace: Typeface = this.labelTypeFace,
        labelStrokeColor: Color = this.labelStrokeColor,
        labelPosition: String = this.labelPosition,
        labelWritingMode: String = this.labelWritingMode,
    ): ImageIcon =
        ImageIcon(
            image = image,
            iconSize = iconSize,
            scale = scale,
            anchor = anchor,
            infoAnchor = infoAnchor,
            debug = debug,
            label = label,
            labelTextColor = labelTextColor,
            labelTextSize = labelTextSize,
            labelTypeFace = labelTypeFace,
            labelStrokeColor = labelStrokeColor,
            labelPosition = labelPosition,
            labelWritingMode = labelWritingMode,
        )
}
