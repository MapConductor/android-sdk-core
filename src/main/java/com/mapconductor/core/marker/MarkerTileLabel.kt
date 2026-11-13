package com.mapconductor.core.marker

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.ResourceProvider
import java.text.BreakIterator
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * タイルに描くとき、アイコン本体とは別に描くラベル。
 *
 * ## なぜ分けるか
 *
 * ラベルを焼き込んだビットマップは、文字列と置き場所の組の数だけ要る。
 * 街路樹のように「樹種 × 向き」でラベルを付け替えると、そのたびに画像を作ることになる。
 * 本体（点やピン）だけを焼き込み、文字はタイルを描くときに直接描けば、画像は本体の種類だけで済む。
 *
 * ネイティブマーカーとして描くときは、従来どおり焼き込んだビットマップ（`toBitmapIcon()`）を使う。
 * どちらも配置は [IconLabelLayout] の同じ計算から出す。ios-sdk の `MarkerTileLabel` と同じ。
 *
 * 長さはすべてアイコンのビットマップの px（`BitmapIcon.size` と同じ単位）。タイルではさらに描画倍率を掛ける。
 */
@InternalMapConductorApi
internal class MarkerTileLabel(
    val text: String,
    val textSizePx: Float,
    val typeface: Typeface,
    val textColor: Int,
    val strokeColor: Int,
    val outlinePx: Float,
    /** ラベルの中心の、アンカー点からのずれ。 */
    val centerOffsetX: Float,
    val centerOffsetY: Float,
    /** 縁取り込みの大きさ。 */
    val width: Float,
    val height: Float,
    val vertical: Boolean,
) {
    /**
     * アンカー点 ([anchorX], [anchorY]) を基準に、[scale] 倍で描く。
     *
     * 書き出し位置は整数の画素に揃える。タイルの原点も整数の画素なので、
     * 境界をまたぐラベルは両側のタイルが同じ画素から描き、継ぎ目で字が割れない。
     */
    fun draw(
        canvas: Canvas,
        anchorX: Float,
        anchorY: Float,
        scale: Float,
    ) {
        LabelTypesetter.draw(
            canvas = canvas,
            text = text,
            centerX = anchorX + centerOffsetX * scale,
            centerY = anchorY + centerOffsetY * scale,
            textSizePx = textSizePx * scale,
            typeface = typeface,
            textColor = textColor,
            strokeColor = strokeColor,
            outlinePx = outlinePx * scale,
            vertical = vertical,
            alignToPixel = true,
        )
    }
}

/**
 * 本体とラベルを分けて渡せるアイコンなら、その 2 つ。それ以外は null。
 *
 * タイル描画は本体を描き、ラベルをタイルに直接描く。どちらも焼き込みのビットマップを作らずに
 * 得られるので、ラベル付きのアイコンを大量に作っても、ネイティブマーカーとして描かれない限り
 * 画像は増えない。型で振り分けるのは [MarkerIconExtent] と同じで、アプリ向けの公開 API に
 * 実装点を出さないため。
 */
@InternalMapConductorApi
internal fun MarkerIconInterface.tileParts(): Pair<BitmapIcon, MarkerTileLabel?>? =
    when (this) {
        is AbstractDefaultIcon -> tileBody to tileLabel
        is ImageIcon -> tileBody to tileLabel
        else -> null
    }

/**
 * ラベル付きのアイコンのはみ出し量（アンカーからいちばん遠い辺まで、ビットマップの px）の、
 * プロセス全体での最大。
 *
 * ラベル付きのアイコンは作った時点で配置を計算するので、ここへ記録する。タイル描画は、
 * 隣のタイルからはみ出してくるラベルを取りこぼさないよう、この値で探す範囲を広げる
 * （[MarkerTileRenderer]）。マーカーに付ける前にアイコンは作られるので、タイルが描き直される
 * 時点ではもう記録されている。
 *
 * 小さくはならない。大きなラベルを一度作ると、以後は少し広めに探す。ios-sdk と同じ。
 */
@InternalMapConductorApi
internal object LabelledIconExtent {
    @Volatile
    var maxPx: Double = 0.0
        private set

    fun note(
        width: Float,
        height: Float,
        anchorX: Float,
        anchorY: Float,
    ) {
        val extent =
            maxOf(
                max(width * anchorX, width * (1f - anchorX)),
                max(height * anchorY, height * (1f - anchorY)),
            ).toDouble()
        synchronized(this) {
            if (extent > maxPx) maxPx = extent
        }
    }
}

/**
 * ラベルの文字の測り方と描き方。焼き込み（[AbstractDefaultIcon] / [ImageIcon]）とタイル
 * （[MarkerTileLabel]）の両方がここを通るので、どちらで描いても同じ字面になる。
 *
 * ## 縦書き
 *
 * `labelWritingMode` は CSS の `writing-mode` と同じ書き方で、`vertical-rl` / `vertical-lr` を
 * 縦書きとして扱う（1 行しかないので左右の違いは出ない）。それ以外は横書き。
 *
 * Canvas には縦組みの機能が無いので、OpenType の縦書き用字形（`vert`）を有効にして、
 * 1 文字ずつ 1em の升に積む。長音の「ー」は縦棒に、小書きの「ョ」は縦書きの位置になる
 * （ios-sdk は CoreText の縦組みで同じ字面を出す）。横の通りに沿って並ぶ街路樹のように、
 * 横書きでは幅を取りすぎて並ばないラベルに使う。
 */
@InternalMapConductorApi
internal object LabelTypesetter {
    const val HORIZONTAL: String = "horizontal-tb"

    /** ラベルの文字の大きさ（px）。sp と em は文字の拡大設定に従い、それ以外は dp として読む。 */
    fun textSizePx(textUnit: TextUnit): Float =
        when (textUnit.type) {
            TextUnitType.Sp -> ResourceProvider.spToPx(textUnit.value.toDouble()).toFloat()
            TextUnitType.Em -> ResourceProvider.spToPx(16.0 * textUnit.value).toFloat()
            else -> ResourceProvider.dpToPx(textUnit.value.toDouble()).toFloat()
        }

    /** 縁取りの太さ（アイコンの拡大率を考慮、最小 2px）。 */
    fun outlinePx(iconScale: Float): Float = max(ResourceProvider.dpToPxForBitmap(1f * iconScale).toFloat(), 2f)

    fun isVertical(writingMode: String): Boolean {
        val mode = writingMode.trim().lowercase()
        return mode == "vertical-rl" || mode == "vertical-lr"
    }

    /**
     * 縁取り込みの大きさ。[contentWidth] は縁取りを除いた字面の幅（横書きは文字列の幅、
     * 縦書きは列の幅）で、ラベルをピンに重ねるときのビットマップの幅に使う。
     */
    @InternalMapConductorApi
    class Metrics(
        val width: Float,
        val height: Float,
        val contentWidth: Float,
    )

    fun measure(
        text: String,
        textSizePx: Float,
        typeface: Typeface,
        outlinePx: Float,
        vertical: Boolean,
    ): Metrics {
        val paint = textPaint(textSizePx, typeface, 0, vertical)
        if (!vertical) {
            val width = paint.measureText(text)
            val fm = paint.fontMetrics
            return Metrics(width + outlinePx * 2f, fm.descent - fm.ascent + outlinePx * 2f, width)
        }
        val glyphs = graphemes(text)
        var column = 0f
        for (glyph in glyphs) column = max(column, paint.measureText(glyph))
        column = ceil(max(column, textSizePx * VERTICAL_COLUMN_MIN_EM))
        return Metrics(column + outlinePx * 2f, glyphs.size * textSizePx + outlinePx * 2f, column)
    }

    /** 中心 ([centerX], [centerY]) に描く。縁取りを先に、塗りを後に重ねる。 */
    fun draw(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        textSizePx: Float,
        typeface: Typeface,
        textColor: Int,
        strokeColor: Int,
        outlinePx: Float,
        vertical: Boolean,
        alignToPixel: Boolean,
    ) {
        val fill = textPaint(textSizePx, typeface, textColor, vertical)
        val outline =
            Paint(fill).apply {
                style = Paint.Style.STROKE
                strokeWidth = outlinePx
                color = strokeColor
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
        val fm = fill.fontMetrics

        fun align(value: Float): Float = if (alignToPixel) value.roundToInt().toFloat() else value

        if (!vertical) {
            val width = fill.measureText(text)
            val baseline = align(centerY + (fm.descent - fm.ascent) / 2f - fm.descent)
            // 中央揃えで描くので、左端を画素に揃えてから中央へ戻す。
            val x = align(centerX - width / 2f) + width / 2f
            canvas.drawText(text, x, baseline, outline)
            canvas.drawText(text, x, baseline, fill)
            return
        }

        val glyphs = graphemes(text)
        val em = textSizePx
        val x = align(centerX)
        val top = align(centerY - glyphs.size * em / 2f)
        // 1em の升の中央に字面の中央が来るベースライン。
        val baselineInCell = em / 2f - (fm.ascent + fm.descent) / 2f
        for ((index, glyph) in glyphs.withIndex()) {
            val baseline = top + index * em + baselineInCell
            canvas.drawText(glyph, x, baseline, outline)
            canvas.drawText(glyph, x, baseline, fill)
        }
    }

    private fun textPaint(
        textSizePx: Float,
        typeface: Typeface,
        color: Int,
        vertical: Boolean,
    ): Paint =
        Paint().apply {
            this.color = color
            this.textSize = textSizePx
            textAlign = Paint.Align.CENTER
            this.typeface = typeface
            isAntiAlias = true
            isSubpixelText = true
            if (vertical) fontFeatureSettings = "'vert' 1"
        }

    /** 書記素（見た目の 1 文字）ごとに分ける。結合文字やサロゲートペアを割らない。 */
    private fun graphemes(text: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val result = ArrayList<String>(text.length)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            result.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return result
    }

    /** 縦書きの列の幅の下限（em）。細い字だけの列でも、隣の列や点と間を空ける。 */
    private const val VERTICAL_COLUMN_MIN_EM = 1f
}

/**
 * アイコンのビットマップの中での、本体とラベルの配置。すべて px。
 *
 * 既定のピンと画像アイコンで共通の計算。react-sdk の `computeIconLabelLayout`、
 * ios-sdk の `DefaultIconLayout` と同じ。
 */
@InternalMapConductorApi
internal object IconLabelLayout {
    @InternalMapConductorApi
    class Result(
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val markerOffsetX: Float,
        val markerOffsetY: Float,
        val labelCenterX: Float,
        val labelCenterY: Float,
    )

    /**
     * @param markerSize 本体の正方形の一辺。
     * @param bodyLeft 〜 [bodyBottom] ラベルを避けて置く、本体の描かれている範囲（本体の左上が原点）。
     * @param centerY `center center` のときのラベルの縦位置。
     * @param sideCenterY 左右に置き、縦を指定しないときのラベルの縦位置。
     * @param label ラベルの寸法。無ければ本体だけ。
     */
    fun compute(
        markerSize: Int,
        bodyLeft: Float,
        bodyRight: Float,
        bodyTop: Float,
        bodyBottom: Float,
        centerY: Float,
        sideCenterY: Float,
        label: LabelTypesetter.Metrics?,
        outlinePx: Float,
        labelPosition: String,
    ): Result {
        val size = markerSize.toFloat()
        if (label == null) {
            return Result(markerSize, markerSize, 0f, 0f, size / 2f, centerY)
        }
        val padding = size * 0.1f // 左右に5%ずつの余白
        val labelWidth = label.width
        val labelHeight = label.height
        val (h, v) = AbstractDefaultIcon.parseLabelPosition(labelPosition)
        val gap = max(outlinePx, size * 0.05f)

        // 本体の左上を原点とした座標でラベルの中心を決める。
        val cx =
            when {
                h < 0 -> bodyLeft - gap - labelWidth / 2f
                h > 0 -> bodyRight + gap + labelWidth / 2f
                else -> size / 2f
            }
        val cy =
            if (h != 0) {
                // 横に置くときは、縦は本体の上端・指定の中心・下端に揃える。
                when {
                    v < 0 -> bodyTop + labelHeight / 2f
                    v > 0 -> bodyBottom - labelHeight / 2f
                    else -> sideCenterY
                }
            } else {
                // 中央列の top / bottom は本体と重ならないよう上下の外側に置く。
                when {
                    v < 0 -> bodyTop - gap - labelHeight / 2f
                    v > 0 -> bodyBottom + gap + labelHeight / 2f
                    else -> centerY
                }
            }

        val bitmapWidth: Int
        val markerOffsetX: Float
        if (h == 0) {
            // 中央なら従来どおり（出力を変えない）。
            bitmapWidth = max(size, label.contentWidth + padding).toInt()
            markerOffsetX = (bitmapWidth - markerSize) / 2f
        } else {
            val minX = min(0f, cx - labelWidth / 2f - padding / 2f)
            val maxX = max(size, cx + labelWidth / 2f + padding / 2f)
            bitmapWidth = ceil(maxX - minX).toInt()
            markerOffsetX = -minX
        }

        val minY = min(0f, cy - labelHeight / 2f)
        val maxY = max(size, cy + labelHeight / 2f)
        val markerOffsetY = ceil(-minY)
        val bitmapHeight = (markerOffsetY + ceil(maxY)).toInt()

        return Result(
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            markerOffsetX = markerOffsetX,
            markerOffsetY = markerOffsetY,
            labelCenterX = markerOffsetX + cx,
            labelCenterY = markerOffsetY + cy,
        )
    }
}
