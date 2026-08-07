package com.mapconductor.core.marker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.mapconductor.settings.Settings
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/**
 * 単色で塗る既定アイコン（カラーフィル版の DefaultIcon）。
 * `DefaultMarkerIcon` はこれの別名。
 */
class ColorDefaultIcon(
    private val fillColor: Color,
    baseProperties: BaseIconProperties,
) : AbstractDefaultIcon(baseProperties) {
    // 便利なコンストラクタ
    constructor(
        fillColor: Color = Color.Red,
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
        fillColor = fillColor,
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
        val fillPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = fillColor.toArgb()
            }
        canvas.drawPath(path, fillPaint)
    }

    override fun getUniqueProperties(): Any = fillColor

    fun copy(
        fillColor: Color = this.fillColor,
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
    ): ColorDefaultIcon =
        ColorDefaultIcon(
            fillColor = fillColor,
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
    ): ColorDefaultIcon = copy(scale = scale, iconSize = iconSize)
}

typealias DefaultMarkerIcon = ColorDefaultIcon
