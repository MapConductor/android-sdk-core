package com.mapconductor.core.marker

/**
 * Options for marker tiling optimization.
 *
 * When enabled, large sets of static markers can be rendered as tile overlays
 * to avoid per-marker add/update cost in native map SDKs.
 */
data class MarkerTilingOptions(
    val enabled: Boolean = true,
    /**
     * When enabled, draws debug overlay onto marker tiles: top/left border lines and a label
     * containing z/x/y and basic render stats. Useful to debug caching/scaling artifacts.
     */
    val debugTileOverlay: Boolean = false,
    val minMarkerCount: Int = 2000,
    val cacheSize: Int = 8 * 1024 * 1024,
    /**
     * Extra scale multiplier applied per marker per zoom during marker tiling.
     *
     * The renderer computes:
     * `effectiveScale = (markerState.icon?.scale ?: 1.0) * (iconScaleCallback?.invoke(markerState, zoom) ?: 1.0)`
     */
    val iconScaleCallback: ((MarkerState, Int) -> Double)? = null,
    /**
     * ビューポート内のマーカーが少ないとき、タイルをやめてネイティブマーカーで描くための設定。
     *
     * タイルはラスターなので地図を回すとアイコンごと傾き、per-marker の動きも持てない。
     * 画面周辺が十分少ないときだけネイティブへ戻して、その 2 つを取り戻す。
     *
     * **既定は無効（[MarkerViewportPolicy.Disabled]）。** 実機で有効性は確認済みだが
     * （MapLibre で切り替え 34ms / Janky 1.7%）、マーカータイリングとの融合は保留中のため
     * 既定では従来どおり常にタイルで描く。有効にするには
     * [MarkerViewportPolicy.Default] を渡す。配線は 6 プロバイダに入ったままなので、
     * この既定値を変えるだけで戻せる。
     */
    val viewport: MarkerViewportPolicy = MarkerViewportPolicy.Disabled,
) {
    companion object {
        val Disabled: MarkerTilingOptions = MarkerTilingOptions(enabled = false)
        val Default: MarkerTilingOptions = MarkerTilingOptions()
    }
}
