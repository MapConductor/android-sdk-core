package com.mapconductor.core.marker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.graphics.withClip
import com.mapconductor.settings.Settings
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/**
 * ピンの中に [Bitmap] を敷く既定アイコン。
 *
 * 画像フィル版のDefaultIcon
 */
class ImageDefaultIcon(
    private val backgroundImage: Bitmap,
    baseProperties: BaseIconProperties,
) : AbstractDefaultIcon(baseProperties) {
    // 便利なコンストラクタ
    constructor(
        backgroundImage: Bitmap,
        strokeColor: Color = Color.White,
        strokeWidth: Dp = Settings.Default.iconStroke,
        scale: Float = 1f,
        label: String? = null,
        labelTextColor: Color? = Color.Black,
        labelTextSize: TextUnit = 18.sp,
        labelTypeFace: Typeface = Typeface.DEFAULT,
        labelStrokeColor: Color = Color.White,
        infoAnchor: Offset = Offset(0.5f, 0f),
        iconSize: Dp = Settings.Default.iconSize,
        debug: Boolean = false,
    ) : this(
        backgroundImage = backgroundImage,
        baseProperties =
            BaseIconProperties(
                strokeColor = strokeColor,
                strokeWidth = strokeWidth,
                scale = scale,
                label = label,
                labelTextColor = labelTextColor,
                labelTextSize = labelTextSize,
                labelTypeFace = labelTypeFace,
                labelStrokeColor = labelStrokeColor,
                infoAnchor = infoAnchor,
                iconSize = iconSize,
                debug = debug,
            ),
    )

    override fun drawMarkerFill(
        canvas: Canvas,
        path: Path,
        canvasSize: Float,
        iconScale: Float,
    ) {
        canvas.withClip(path) {
            // マーカー形状でクリッピング

            // 背景画像をマーカーサイズにスケーリングして描画
            // アスペクト比を保持してセンタークロップ
            val bitmapWidth = backgroundImage.width.toFloat()
            val bitmapHeight = backgroundImage.height.toFloat()
            val bitmapRatio = bitmapWidth / bitmapHeight
            val canvasRatio = 1f // 正方形のキャンバス

            val matrix = Matrix()

            if (bitmapRatio > canvasRatio) {
                // ビットマップが横長の場合：高さを合わせてセンタリング
                val scale = canvasSize / bitmapHeight
                val scaledWidth = bitmapWidth * scale
                val offsetX = (canvasSize - scaledWidth) / 2f
                matrix.setScale(scale, scale)
                matrix.postTranslate(offsetX, 0f)
            } else {
                // ビットマップが縦長または正方形の場合：幅を合わせてセンタリング
                val scale = canvasSize / bitmapWidth
                val scaledHeight = bitmapHeight * scale
                val offsetY = (canvasSize - scaledHeight) / 2f
                matrix.setScale(scale, scale)
                matrix.postTranslate(0f, offsetY)
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            drawBitmap(backgroundImage, matrix, paint)
        }
    }

    override fun getUniqueProperties(): Any {
        // Bitmapの内容をハッシュ化して比較用に使用
        return backgroundImage.let { bitmap ->
            val buffer = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(buffer, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            buffer.contentHashCode()
        }
    }

    fun copy(
        backgroundImage: Bitmap = this.backgroundImage,
        strokeColor: Color = this.strokeColor,
        strokeWidth: Dp = this.strokeWidth,
        scale: Float = this.scale,
        label: String? = this.label,
        labelTextColor: Color? = this.labelTextColor,
        labelTextSize: TextUnit = this.labelTextSize,
        labelTypeFace: Typeface = this.labelTypeFace,
        labelStrokeColor: Color = this.labelStrokeColor,
        iconSize: Dp = this.iconSize,
        debug: Boolean = this.debug,
    ): ImageDefaultIcon =
        ImageDefaultIcon(
            backgroundImage = backgroundImage,
            baseProperties =
                baseProperties.copy(
                    strokeColor = strokeColor,
                    strokeWidth = strokeWidth,
                    scale = scale,
                    label = label,
                    labelTextColor = labelTextColor,
                    labelTextSize = labelTextSize,
                    labelTypeFace = labelTypeFace,
                    labelStrokeColor = labelStrokeColor,
                    iconSize = iconSize,
                    debug = debug,
                ),
        )

    fun copy(
        scale: Float,
        iconSize: Dp,
    ): ImageDefaultIcon = copy(scale = scale, iconSize = iconSize)
}
