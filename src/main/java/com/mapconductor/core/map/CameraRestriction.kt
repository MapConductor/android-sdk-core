package com.mapconductor.core.map

import com.mapconductor.core.features.GeoRectBounds
import java.io.Serializable

/**
 * カメラの可動範囲を制限する設定。
 *
 * - [bounds] : カメラ（ビューポート）がこの矩形の外へ出られないように制限する。null で無制限。
 * - [minZoom] / [maxZoom] : ズームの下限・上限。値は統一ズーム（Google Maps 準拠, 0..22 相当）で指定し、
 *   各プロバイダが自身のズーム体系へ変換して適用する。null で無制限。
 *
 * React SDK の `restrictBounds` / `minZoom` / `maxZoom` プロパティに対応する。
 */
data class CameraRestriction(
    val bounds: GeoRectBounds? = null,
    val minZoom: Double? = null,
    val maxZoom: Double? = null,
) : Serializable {
    val isEmpty: Boolean
        get() = (bounds == null || bounds.isEmpty) && minZoom == null && maxZoom == null

    companion object {
        val None = CameraRestriction()
    }
}
