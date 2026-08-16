package com.mapconductor.core.map

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface

interface MapViewHolderInterface<ActualMapView, ActualMap> {
    val mapView: ActualMapView
    val map: ActualMap

    /**
     * 地理座標 → 画面座標。
     *
     * 同期でしか用意できない SDK があるためこの形。逆変換と違って非同期版は無い。
     */
    fun toScreenOffset(position: GeoPointInterface): Offset?

    /**
     * 画面座標 → 地理座標（非同期）。
     *
     * 既定は [fromScreenOffsetSync] へ委譲する。同期で変換できるプロバイダは
     * [fromScreenOffsetSync] だけを実装すればよい（実際 5 プロバイダが
     * `= fromScreenOffsetSync(offset)` の 1 行を書いていた）。
     *
     * 同期版を持てない SDK（WebView ブリッジ越しなど）はこちらを実装する。
     */
    suspend fun fromScreenOffset(offset: Offset): GeoPoint? = fromScreenOffsetSync(offset)

    /**
     * 画面座標 → 地理座標（同期）。**同期変換を必須にしないこと。**
     *
     * WebView ブリッジ越しのプロバイダ（android-for-longdo）は同期 API を持たず、
     * ここは null を返すしかない。同期変換を要求する機能（InfoBubble・マーカー
     * アニメーション・タイル方式マーカーのヒットテスト）は、動かないことを
     * [com.mapconductor.core.map.MapCapability.ScreenProjectionSync] で宣言すること。
     * 既定 null のまま黙って無反応にしない。
     */
    fun fromScreenOffsetSync(offset: Offset): GeoPoint? = null
}
