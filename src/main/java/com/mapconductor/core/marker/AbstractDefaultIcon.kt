package com.mapconductor.core.marker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.mapconductor.core.BitmapIconCache
import com.mapconductor.core.ResourceProvider
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/**
 * 既定マーカーアイコンの土台。ピン形の輪郭・ラベル・キャッシュを受け持ち、
 * 中身の塗り方だけをサブクラスに任せる。
 *
 * `toBitmapIcon()` の結果は [BitmapIconCache] に入れる。同じ見た目のアイコンは
 * 何千個のマーカーで共有されるので、毎回 Bitmap を作ると描画より確保が重くなる。
 * キャッシュキーは見た目を決める全プロパティの hash（`getUniqueProperties()` を含む）。
 */
abstract class AbstractDefaultIcon(
    protected val baseProperties: BaseIconProperties,
) : AbstractMarkerIcon() {
    data class BaseIconProperties(
        val strokeColor: Color,
        val strokeWidth: Dp,
        val scale: Float,
        val label: String?,
        val labelTextColor: Color?,
        val labelTextSize: TextUnit,
        val labelTypeFace: Typeface,
        val labelStrokeColor: Color,
        val infoAnchor: Offset,
        val iconSize: Dp,
        val debug: Boolean,
        val labelPosition: String = DEFAULT_LABEL_POSITION,
        val labelWritingMode: String = DEFAULT_LABEL_WRITING_MODE,
    )

    // 共通プロパティの委譲
    val strokeColor: Color by baseProperties::strokeColor
    val strokeWidth: Dp by baseProperties::strokeWidth
    final override val scale: Float by baseProperties::scale
    val label: String? by baseProperties::label
    val labelTextColor: Color? by baseProperties::labelTextColor
    val labelTextSize: TextUnit by baseProperties::labelTextSize
    val labelTypeFace: Typeface by baseProperties::labelTypeFace
    val labelStrokeColor: Color by baseProperties::labelStrokeColor
    val labelPosition: String by baseProperties::labelPosition

    /**
     * ラベルの書字方向。CSS の `writing-mode` と同じ書き方で、`horizontal-tb`（既定）か
     * `vertical-rl` / `vertical-lr`（縦書き）。縦書きは 1 文字ぶんの幅で縦に並ぶので、
     * 横に詰まって並ぶ点にも名前を付けやすい。
     */
    val labelWritingMode: String by baseProperties::labelWritingMode
    final override val iconSize: Dp by baseProperties::iconSize
    final override val debug: Boolean by baseProperties::debug

    /**
     * ピン先端の位置（ビットマップに対する割合）。ラベルをピンの横や上に置くと
     * ビットマップが片側へ伸びるので、固定の (0.5, 1) ではなくレイアウトから求める。
     */
    final override val anchor: Offset by lazy {
        val layout = layout
        Offset(
            (layout.markerOffsetX + layout.markerSize / 2f) / layout.bitmapWidth,
            (layout.markerOffsetY + layout.markerSize) / layout.bitmapHeight,
        )
    }

    /**
     * 指定値はピン本体に対する割合として扱い、ビットマップに対する割合へ直して返す。
     * ラベルが中央にある限りは指定値と一致する（従来どおり）。
     */
    final override val infoAnchor: Offset by lazy {
        val layout = layout
        val requested = baseProperties.infoAnchor
        Offset(
            (layout.markerOffsetX + requested.x * layout.markerSize) / layout.bitmapWidth,
            (layout.markerOffsetY + requested.y * layout.markerSize) / layout.bitmapHeight,
        )
    }

    /**
     * ビットマップの幅と高さが、ピン本体（iconSize × scale の正方形）の何倍か。
     * ラベルを横や上に置くと 1 を超える。当たり判定と吹き出しがアイコンの実寸を使うためのもの。
     */
    internal val extentRatio: Size by lazy {
        val layout = layout
        Size(
            layout.bitmapWidth.toFloat() / layout.markerSize,
            layout.bitmapHeight.toFloat() / layout.markerSize,
        )
    }

    /** ビットマップ内でのマーカー本体とラベルの配置。すべてピクセル空間。 */
    private class IconLayout(
        val markerSize: Int,
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val markerOffsetX: Float,
        val markerOffsetY: Float,
        val labelCenterX: Float,
        val labelCenterY: Float,
        val label: LabelTypesetter.Metrics?,
    )

    private val layout: IconLayout by lazy { computeLayout() }

    init {
        // ラベル付きは、はみ出し量を作った時点で記録する（[LabelledIconExtent]）。
        // JVM の単体テストでは Paint が空実装なので、測れなければ記録しない。
        if (baseProperties.label != null) {
            runCatching { layout }.getOrNull()?.let { layout ->
                LabelledIconExtent.note(
                    width = layout.bitmapWidth.toFloat(),
                    height = layout.bitmapHeight.toFloat(),
                    anchorX = (layout.markerOffsetX + layout.markerSize / 2f) / layout.bitmapWidth,
                    anchorY = (layout.markerOffsetY + layout.markerSize) / layout.bitmapHeight,
                )
            }
        }
    }

    /**
     * ラベルを除いたピン。タイル描画が使う。
     *
     * 色や大きさが同じなら、ラベルの違うアイコンの間で 1 枚を共有する（[BitmapIconCache] の
     * キーにラベルを含めない）。街路樹のように「樹種 × 向き」でラベルを付け替えても、
     * 焼き込む画像は本体の種類だけで済む。
     */
    internal val tileBody: BitmapIcon
        get() {
            if (label == null) return toBitmapIcon()
            val bodyProperties =
                baseProperties.copy(
                    label = null,
                    labelPosition = DEFAULT_LABEL_POSITION,
                    labelWritingMode = DEFAULT_LABEL_WRITING_MODE,
                )
            val id = "default_icon_body_${bodyProperties.hashCode() * 31 + getUniqueProperties().hashCode()}".hashCode()
            BitmapIconCache.get(id)?.let { return it }
            val markerSize = layout.markerSize
            val bitmap = createBitmap(markerSize, markerSize)
            ResourceProvider.getBitmapDensity().let { density ->
                bitmap.density = (density * android.util.DisplayMetrics.DENSITY_DEFAULT).toInt()
            }
            drawMarker(
                canvas = Canvas(bitmap),
                canvasSize = markerSize.toFloat(),
                iconScale = scale,
            )
            val result =
                BitmapIcon(
                    bitmap = bitmap,
                    anchor = Offset(0.5f, 1f),
                    size = Size(markerSize.toFloat(), markerSize.toFloat()),
                )
            BitmapIconCache.put(id, result)
            return result
        }

    internal val tileLabel: MarkerTileLabel?
        get() {
            val text = label ?: return null
            val layout = layout
            val metrics = layout.label ?: return null
            val anchorX = layout.markerOffsetX + layout.markerSize / 2f
            val anchorY = layout.markerOffsetY + layout.markerSize
            return MarkerTileLabel(
                text = text,
                textSizePx = convertTextUnitToPx(labelTextSize, 1f),
                typeface = labelTypeFace,
                textColor = (labelTextColor ?: Color.Black).toArgb(),
                strokeColor = labelStrokeColor.toArgb(),
                outlinePx = labelOutlineStrokeWidth(scale),
                centerOffsetX = layout.labelCenterX - anchorX,
                centerOffsetY = layout.labelCenterY - anchorY,
                width = metrics.width,
                height = metrics.height,
                vertical = LabelTypesetter.isVertical(labelWritingMode),
            )
        }

    /**
     * サブクラスでマーカーのフィル描画を実装する
     */
    protected abstract fun drawMarkerFill(
        canvas: Canvas,
        path: Path,
        canvasSize: Float,
        iconScale: Float,
    )

    /**
     * サブクラスで等価性比較のための固有プロパティを返す
     */
    protected abstract fun getUniqueProperties(): Any

    override fun toBitmapIcon(): BitmapIcon {
        val id = "default_icon_${hashCode()}".hashCode()
        BitmapIconCache.get(id)?.let {
            return it
        }

        val layout = layout
        val bitmap = createBitmap(layout.bitmapWidth, layout.bitmapHeight)
        // Set bitmap density based on override (e.g., 1.0 for MapLibre to prevent auto-scaling)
        ResourceProvider.getBitmapDensity().let { density ->
            bitmap.density = (density * android.util.DisplayMetrics.DENSITY_DEFAULT).toInt()
        }
        val canvas = Canvas(bitmap)

        // Draw marker (scale is already applied in markerSize)
        drawMarker(
            canvas = canvas,
            canvasSize = layout.markerSize.toFloat(),
            iconScale = scale,
            offsetX = layout.markerOffsetX,
            offsetY = layout.markerOffsetY,
        )

        // Draw label
        drawLabel(
            canvas = canvas,
            iconScale = scale,
            textSize = labelTextSize,
            centerX = layout.labelCenterX,
            centerY = layout.labelCenterY,
        )

        val adjustedAnchor = anchor

        val result =
            BitmapIcon(
                bitmap = bitmap,
                anchor = adjustedAnchor,
                size = Size(layout.bitmapWidth.toFloat(), layout.bitmapHeight.toFloat()),
            )
        BitmapIconCache.put(id, result)
        return result
    }

    /**
     * react-sdk / ios-sdk と同じレイアウト計算。`labelPosition` が中央なら従来どおり
     * ラベルを円形部分の中心に重ね、ビットマップを左右対称に広げる。中央以外なら
     * ラベルをピンの輪郭の外側へ置き、ビットマップをその側へだけ伸ばす。
     */
    private fun computeLayout(): IconLayout {
        val baseCanvasSize = ResourceProvider.dpToPxForBitmap(iconSize.value)
        val markerSize = (baseCanvasSize * scale).toInt()
        val size = markerSize.toFloat()
        // 円形部分の中心
        val headCenterY = size * 0.35f
        val pin = markerPinBounds(size, scale)
        val outline = labelOutlineStrokeWidth(scale)
        val metrics =
            label?.let { text ->
                LabelTypesetter.measure(
                    text = text,
                    textSizePx = convertTextUnitToPx(labelTextSize, 1f),
                    typeface = labelTypeFace,
                    outlinePx = outline,
                    vertical = LabelTypesetter.isVertical(labelWritingMode),
                )
            }
        val result =
            IconLabelLayout.compute(
                markerSize = markerSize,
                bodyLeft = pin.left,
                bodyRight = pin.right,
                bodyTop = pin.top,
                bodyBottom = pin.bottom,
                centerY = headCenterY,
                // 横に置くときの縦の中央は、ピンの上端と先端の中間。頭の中心にすると、
                // ピン全体の中央より上に見える。
                sideCenterY = (pin.top + pin.bottom) / 2f,
                label = metrics,
                outlinePx = outline,
                labelPosition = labelPosition,
            )
        return IconLayout(
            markerSize = markerSize,
            bitmapWidth = result.bitmapWidth,
            bitmapHeight = result.bitmapHeight,
            markerOffsetX = result.markerOffsetX,
            markerOffsetY = result.markerOffsetY,
            labelCenterX = result.labelCenterX,
            labelCenterY = result.labelCenterY,
            label = metrics,
        )
    }

    private class PinBounds(
        val left: Float,
        val right: Float,
        val top: Float,
        val bottom: Float,
    )

    /**
     * ピンの見えている輪郭（設計座標で x: 4〜20, y: 0〜24）を px で返す。
     * 外接箱（23.5 × 25.6）より細いので、ラベルを横に置くときはこちらに沿わせる。
     */
    private fun markerPinBounds(
        canvasSize: Float,
        iconScale: Float,
    ): PinBounds {
        val metrics = markerPathMetrics(canvasSize, iconScale)
        val s = metrics.markerScale
        return PinBounds(
            left = metrics.offsetX + 4f * s,
            right = metrics.offsetX + 20f * s,
            top = metrics.offsetY,
            bottom = metrics.offsetY + 24f * s,
        )
    }

    private class MarkerPathMetrics(
        val markerScale: Float,
        val offsetX: Float,
        val offsetY: Float,
    )

    private fun markerPathMetrics(
        canvasSize: Float,
        iconScale: Float,
    ): MarkerPathMetrics {
        val originalSize = Size(23.5f, 25.6f)

        // Since canvasSize is already scaled (baseCanvasSize * scale),
        // we don't need to apply iconScale again to the markerScale calculation
        val scaledStrokeWidth =
            ResourceProvider
                .dpToPxForBitmap(strokeWidth.value * iconScale)
                .toFloat()

        // Reserve space for stroke on sides and top, but not bottom (point should touch edge)
        val epsilon = 0.75f
        val padding = (scaledStrokeWidth / 2f - epsilon).coerceAtLeast(0f)
        val availableWidth = canvasSize - (padding * 2f)
        val availableHeight = canvasSize - padding // Only top padding, bottom point touches edge

        // Calculate scale to fit marker within available space
        // DO NOT multiply by iconScale here as canvasSize already includes it
        val markerScale =
            minOf(
                availableWidth / originalSize.width,
                availableHeight / originalSize.height,
            )

        val scaledWidth = originalSize.width * markerScale
        val scaledHeight = originalSize.height * markerScale

        // Center horizontally, align bottom point to canvas edge
        // The path's bottom point should touch the canvas bottom, the stroke will extend beyond
        val offsetX = (canvasSize - scaledWidth) / 2f
        val offsetY = (canvasSize - scaledHeight + (strokeWidth.value * markerScale)) / 2f
        return MarkerPathMetrics(markerScale, offsetX, offsetY)
    }

    /**
     * マーカー本体の描画（共通処理）
     *
     * マーカーは canvas を (offsetX, offsetY) だけずらしてから描く。サブクラスの
     * [drawMarkerFill] は常に原点にある canvasSize の正方形を前提にしてよい。
     */
    protected fun drawMarker(
        canvas: Canvas,
        canvasSize: Float,
        iconScale: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        // デバッグ用の枠描画
        if (debug) {
            drawDebugFrame(canvas)
        }

        canvas.withTranslation(offsetX, offsetY) {
            val strokePath = createMarkerPath(canvasSize, iconScale)

            // フィル描画（サブクラス実装）
            drawMarkerFill(this, strokePath, canvasSize, iconScale)

            // ストローク描画（共通）
            val strokePaint = createStrokePaint(iconScale)
            drawPath(strokePath, strokePaint)
        }
    }

    /**
     * マーカーのパスを生成
     */
    protected fun createMarkerPath(
        canvasSize: Float,
        iconScale: Float,
        horizontalOffset: Float = 0f,
    ): Path {
        val metrics = markerPathMetrics(canvasSize, iconScale)
        val markerScale = metrics.markerScale
        val offsetX = metrics.offsetX + horizontalOffset
        val offsetY = metrics.offsetY

        return Path().apply {
            moveTo(12f * markerScale + offsetX, 0f * markerScale + offsetY)

            rCubicTo(
                -4.4183f * markerScale,
                2.3685e-15f * markerScale,
                -8f * markerScale,
                3.5817f * markerScale,
                -8f * markerScale,
                8f * markerScale,
            )

            rCubicTo(
                0f * markerScale,
                1.421f * markerScale,
                0.3816f * markerScale,
                2.75f * markerScale,
                1.0312f * markerScale,
                3.906f * markerScale,
            )

            rCubicTo(
                0.1079f * markerScale,
                0.192f * markerScale,
                0.221f * markerScale,
                0.381f * markerScale,
                0.3438f * markerScale,
                0.563f * markerScale,
            )

            rLineTo(6.625f * markerScale, 11.531f * markerScale)
            rLineTo(6.625f * markerScale, -11.531f * markerScale)

            rCubicTo(
                0.102f * markerScale,
                -0.151f * markerScale,
                0.19f * markerScale,
                -0.311f * markerScale,
                0.281f * markerScale,
                -0.469f * markerScale,
            )

            rLineTo(0.063f * markerScale, -0.094f * markerScale)

            rCubicTo(
                0.649f * markerScale,
                -1.156f * markerScale,
                1.031f * markerScale,
                -2.485f * markerScale,
                1.031f * markerScale,
                -3.906f * markerScale,
            )

            rCubicTo(
                0f * markerScale,
                -4.4183f * markerScale,
                -3.582f * markerScale,
                -8f * markerScale,
                -8f * markerScale,
                -8f * markerScale,
            )

            close()
        }
    }

    /**
     * ストローク用のPaintを作成
     */
    protected fun createStrokePaint(iconScale: Float): Paint =
        Paint().apply {
            color = strokeColor.toArgb()
            style = Paint.Style.STROKE
            strokeWidth =
                ResourceProvider
                    .dpToPxForBitmap(
                        this@AbstractDefaultIcon.strokeWidth.value * iconScale,
                    ).toFloat()
            isAntiAlias = true
            strokeJoin = Paint.Join.ROUND // 追加
            strokeCap = Paint.Cap.ROUND // 追加（尖り部のにじみ軽減）
        }

    // アウトライン幅（アイコンスケールを考慮したストローク幅、最小2px）
    private fun labelOutlineStrokeWidth(iconScale: Float): Float = LabelTypesetter.outlinePx(iconScale)

    /**
     * ラベルテキストの描画。(centerX, centerY) は [computeLayout] が決めたラベルの中心で、
     * 既定ではマーカーの円形部分の中心になる。
     */
    protected fun drawLabel(
        canvas: Canvas,
        iconScale: Float,
        textSize: TextUnit,
        centerX: Float,
        centerY: Float,
    ) {
        label?.let { labelText ->
            LabelTypesetter.draw(
                canvas = canvas,
                text = labelText,
                centerX = centerX,
                centerY = centerY,
                textSizePx = convertTextUnitToPx(textSize, 1f),
                typeface = labelTypeFace,
                textColor = (labelTextColor ?: Color.Black).toArgb(),
                strokeColor = labelStrokeColor.toArgb(),
                outlinePx = labelOutlineStrokeWidth(iconScale),
                vertical = LabelTypesetter.isVertical(labelWritingMode),
                alignToPixel = false,
            )
        }
    }

    /**
     * TextUnitをピクセルサイズに変換
     */
    protected fun convertTextUnitToPx(
        textUnit: TextUnit,
        scale: Float,
    ): Float =
        when (textUnit.type) {
            TextUnitType.Sp -> {
                val spValue = textUnit.value * scale
                ResourceProvider.spToPx(spValue.toDouble()).toFloat()
            }
            TextUnitType.Em -> {
                val baseFontSize = 16f
                val emValue = baseFontSize * textUnit.value * scale
                ResourceProvider.spToPx(emValue.toDouble()).toFloat()
            }
            else -> {
                val dpValue = textUnit.value * scale
                ResourceProvider.dpToPx(dpValue.toDouble()).toFloat()
            }
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false
        other as AbstractDefaultIcon
        return baseProperties == other.baseProperties &&
            getUniqueProperties() == other.getUniqueProperties()
    }

    override fun hashCode(): Int = baseProperties.hashCode() * 31 + getUniqueProperties().hashCode()

    override fun toString(): String =
        "${this::class.simpleName}(baseProperties=$baseProperties, uniqueProperties=${getUniqueProperties()})"

    companion object {
        /**
         * ラベルの既定位置。ピンの頭に重ねる（従来どおり）。
         *
         * `labelPosition` は CSS の `background-position` と同じ書き方で、横
         * （left / center / right）と縦（top / center / bottom）のキーワードを空白区切りで
         * 並べる。順序は問わず、片方だけなら残りは center。
         * - left / right はピンの外側に置き、縦はピンの上端・上端と先端の中間・先端に揃える。
         * - center 列の top / bottom はピンの上・下の外側に置く。
         */
        const val DEFAULT_LABEL_POSITION: String = "center center"

        /** ラベルの既定の書字方向。横書き。書き方は [labelWritingMode] を参照。 */
        const val DEFAULT_LABEL_WRITING_MODE: String = LabelTypesetter.HORIZONTAL

        /** `labelPosition` を横・縦それぞれ -1 / 0 / 1 に読み替える。読めない値は中央扱い。 */
        internal fun parseLabelPosition(position: String): Pair<Int, Int> {
            var h = 0
            var v = 0
            position.trim().lowercase().split(Regex("\\s+")).forEach { word ->
                when (word) {
                    "left" -> h = -1
                    "right" -> h = 1
                    "top" -> v = -1
                    "bottom" -> v = 1
                }
            }
            return h to v
        }
    }
}
