package com.mapconductor.core.marker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.graphics.withClip
import com.mapconductor.settings.Settings
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/**
 * ピンの中に [Drawable] を敷く既定アイコン。ベクタ drawable を渡せるので、
 * 解像度に依らず輪郭が保たれる。
 *
 * Drawable フィル版のDefaultIcon
 */
class DrawableDefaultIcon(
    private val backgroundDrawable: Drawable,
    baseProperties: BaseIconProperties,
) : AbstractDefaultIcon(baseProperties) {
    // 便利なコンストラクタ
    constructor(
        backgroundDrawable: Drawable,
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
        backgroundDrawable = backgroundDrawable,
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

            // Drawableのサイズを設定
            val canvasInt = canvasSize.toInt()

            // Drawableの固有サイズを取得
            val intrinsicWidth = backgroundDrawable.intrinsicWidth
            val intrinsicHeight = backgroundDrawable.intrinsicHeight

            if (intrinsicWidth > 0 && intrinsicHeight > 0) {
                // 固有サイズがある場合：アスペクト比を保持してセンタークロップ
                val drawableRatio = intrinsicWidth.toFloat() / intrinsicHeight.toFloat()
                val canvasRatio = 1f // 正方形のキャンバス

                val bounds =
                    if (drawableRatio > canvasRatio) {
                        // Drawableが横長の場合：高さを合わせてセンタリング
                        val scaledWidth = (canvasInt * drawableRatio).toInt()
                        val offsetX = (canvasInt - scaledWidth) / 2
                        Rect(offsetX, 0, offsetX + scaledWidth, canvasInt)
                    } else {
                        // Drawableが縦長または正方形の場合：幅を合わせてセンタリング
                        val scaledHeight = (canvasInt / drawableRatio).toInt()
                        val offsetY = (canvasInt - scaledHeight) / 2
                        Rect(0, offsetY, canvasInt, offsetY + scaledHeight)
                    }

                backgroundDrawable.bounds = bounds
            } else {
                // 固有サイズがない場合：キャンバス全体に描画
                backgroundDrawable.setBounds(0, 0, canvasInt, canvasInt)
            }

            backgroundDrawable.draw(this)
        }
    }

    override fun getUniqueProperties(): Any {
        // Drawableの識別用にクラス名とパラメータのハッシュを使用
        return when (backgroundDrawable) {
            is BitmapDrawable -> {
                // BitmapDrawableの場合はビットマップの内容をハッシュ化
                backgroundDrawable.bitmap?.let { bitmap ->
                    if (bitmap.isRecycled) {
                        // リサイクルされたビットマップの場合はDrawableのハッシュを使用
                        backgroundDrawable.hashCode()
                    } else {
                        try {
                            // サンプリングサイズを計算（メモリ効率のため）
                            val sampleWidth = minOf(bitmap.width, 32)
                            val sampleHeight = minOf(bitmap.height, 32)
                            val bufferSize = sampleWidth * sampleHeight
                            val buffer = IntArray(bufferSize)

                            // 正しい引数でgetPixelsを呼び出し
                            bitmap.getPixels(
                                buffer, // pixels配列
                                0, // offset
                                sampleWidth, // stride（一行のピクセル数）
                                0, // x開始位置
                                0, // y開始位置
                                sampleWidth, // 取得する幅
                                sampleHeight, // 取得する高さ
                            )
                            buffer.contentHashCode()
                        } catch (e: Exception) {
                            // getPixelsでエラーが発生した場合はDrawableのハッシュを使用
                            backgroundDrawable.hashCode()
                        }
                    }
                } ?: backgroundDrawable.hashCode()
            }
            is ColorDrawable -> {
                // ColorDrawableの場合は色の値を使用
                backgroundDrawable.color
            }
            is GradientDrawable -> {
                // ConstantStateは同一リソースから生成されたDrawable間で共有されるため
                // identityHashCodeはリコンポジションをまたいで安定する
                System.identityHashCode(backgroundDrawable.constantState ?: backgroundDrawable)
            }
            else -> {
                System.identityHashCode(backgroundDrawable.constantState ?: backgroundDrawable)
            }
        }
    }

    fun copy(
        backgroundDrawable: Drawable = this.backgroundDrawable,
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
    ): DrawableDefaultIcon =
        DrawableDefaultIcon(
            backgroundDrawable = backgroundDrawable,
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
    ): DrawableDefaultIcon = copy(scale = scale, iconSize = iconSize)
}
