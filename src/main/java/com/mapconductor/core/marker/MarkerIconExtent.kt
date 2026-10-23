package com.mapconductor.core.marker

import androidx.compose.ui.geometry.Size
import com.mapconductor.core.InternalMapConductorApi

/**
 * アイコンが画面上で占める矩形が、ピン本体（iconSize × scale の正方形）の何倍か。
 * `anchor` / `infoAnchor` はこの矩形に対する割合なので、当たり判定と吹き出しはこれを掛けて使う。
 *
 * 既定アイコンはラベルの分だけビットマップが広いことがあるのでその倍率を返し、
 * それ以外のアイコンは従来どおり正方形（1 × 1）として扱う。
 * react-sdk の `markerIconExtentPx`、ios-sdk の `MarkerIconMetrics` に対応する。
 */
@InternalMapConductorApi
object MarkerIconExtent {
    private val SQUARE = Size(1f, 1f)

    fun ratio(icon: MarkerIconInterface): Size = (icon as? AbstractDefaultIcon)?.extentRatio ?: SQUARE
}
