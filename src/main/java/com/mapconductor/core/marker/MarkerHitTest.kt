package com.mapconductor.core.marker

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.ResourceProvider
import com.mapconductor.settings.Settings

/**
 * マーカーのタップ判定（スクリーン空間）。
 *
 * 「アイコンの矩形 + [Settings.Default] の tapTolerance」で判定する。半径固定の円で判定すると、
 * 大きいアイコンは端をタップしても反応せず、小さいアイコンは離れた場所でも反応してしまう。
 * アイコンの実寸とアンカーを使うことで、見た目どおりの当たり判定になる。
 *
 * ios-sdk の `MarkerHitTest.hitsIcon`（`ios-sdk-core`）と同一の判定式。以前は
 * [StrategyMarkerController] と MapLibre / Mapbox / ArcGIS / HERE の各
 * `MarkerController.find()` に同じコードが 5 重に複製されていたため、ここへ集約した。
 */
object MarkerHitTest {
    /**
     * タップ点がマーカーアイコンの矩形（＋許容量）に入っているか。
     *
     * @param touchScreen タップ位置（ビューのピクセル座標）。
     * @param markerScreen マーカーのアンカー点を投影したビュー座標。
     * @param state 判定対象のマーカー。
     * @param defaultIcon [state] がアイコンを持たないときに使う既定アイコン。
     * @param density 画面密度（dp → px）。既定では端末の値を使う。テストからは明示的に渡す。
     *
     * アンカーは「アイコン内のどこがマーカー位置に一致するか」を 0..1 で表す。例えば
     * ピン形状の既定アンカー `(0.5, 1.0)` なら、矩形はアンカー点から上方向へ広がる。
     *
     * アイコンは正方形のキャンバスに描かれる前提で、`iconSize`（dp）× `scale` を
     * 画面密度でピクセルへ換算して使う。
     */
    fun hitsIcon(
        touchScreen: Offset,
        markerScreen: Offset,
        state: MarkerState,
        defaultIcon: MarkerIconInterface = DefaultMarkerIcon(),
        density: Double = ResourceProvider.getDensity().toDouble(),
    ): Boolean {
        val icon = state.icon ?: defaultIcon
        val iconSizePx = icon.iconSize.value.toDouble() * icon.scale.toDouble() * density

        return hitsIconRect(
            dx = (touchScreen.x - markerScreen.x).toDouble(),
            dy = (touchScreen.y - markerScreen.y).toDouble(),
            iconWidthPx = iconSizePx,
            iconHeightPx = iconSizePx,
            anchorX = icon.anchor.x.toDouble(),
            anchorY = icon.anchor.y.toDouble(),
            tolerancePx =
                Settings.Default.tapTolerance.value
                    .toDouble() * density,
        )
    }

    /**
     * アンカー相対のオフセット [dx] / [dy] が、アイコン矩形 + [tolerancePx] に入っているか。
     *
     * 画面密度や [Settings] を参照しない純粋な幾何判定なので、JVM のユニットテストから直接叩ける。
     */
    internal fun hitsIconRect(
        dx: Double,
        dy: Double,
        iconWidthPx: Double,
        iconHeightPx: Double,
        anchorX: Double,
        anchorY: Double,
        tolerancePx: Double,
    ): Boolean {
        val left = -anchorX * iconWidthPx - tolerancePx
        val right = (1.0 - anchorX) * iconWidthPx + tolerancePx
        val top = -anchorY * iconHeightPx - tolerancePx
        val bottom = (1.0 - anchorY) * iconHeightPx + tolerancePx

        return dx in left..right && dy in top..bottom
    }
}
