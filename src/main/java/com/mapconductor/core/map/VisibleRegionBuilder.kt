package com.mapconductor.core.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.mapconductor.core.features.GeoRectBounds
import android.view.View

/**
 * ビューポートの 4 隅を逆投影して [VisibleRegion] を組み立てる。
 *
 * android-for-maplibre / mapbox / tomtom が同じ 35 行前後を各自持っていたものの集約。
 *
 * ## 使えないプロバイダがある
 *
 * すべてのプロバイダがこの形ではない。無理に寄せないこと。
 *  - **googlemaps**: ネイティブの `projection.visibleRegion` を使う。SDK が返す値の方が
 *    正確なので 4 隅の逆投影に置き換えない。
 *  - **here**: bounds はネイティブの `boundingBox` から取り、4 隅だけ逆投影する。
 *  - **arcgis**: 1px 内側の座標を使い、かつ逆投影が suspend（同期版が無い）。
 *  - **maptiler / longdo**: WebView ブリッジで同期の逆投影を持たない
 *    （[MapCapability.ScreenProjectionSync] を参照）。
 *
 * @param inset 端から何 px 内側の点を使うか。0 なら端ちょうど。
 * @param requireAllCorners `true`（既定）なら 4 隅すべてが解けないと null を返す。
 *   `false` なら解けた隅だけで bounds を作り、解けなかった隅は null のまま残す。
 *   傾けた地図や球体表示では隅の逆投影が地表に当たらないことがあり、そこで
 *   [VisibleRegion] ごと落とすと marker-clustering がビューポートを算出できず
 *   クラスタが一切描画されなくなる。それを避けたいプロバイダ（TomTom）が `false` を使う。
 * @return 隅が 1 つも解けなければ null。
 */
fun MapViewHolderInterface<*, *>.buildVisibleRegion(
    inset: Float = 0f,
    requireAllCorners: Boolean = true,
): VisibleRegion? {
    val size = viewportSizePx() ?: return null
    return buildVisibleRegion(size, inset, requireAllCorners)
}

/**
 * ビューポートのサイズを明示して [buildVisibleRegion] する。
 *
 * サイズの解決手段を持たない呼び出し元（ネイティブビューが無いプロバイダ）と、
 * `android.view.View` を用意できないユニットテストのためのオーバーロード。
 */
fun MapViewHolderInterface<*, *>.buildVisibleRegion(
    size: Size,
    inset: Float = 0f,
    requireAllCorners: Boolean = true,
): VisibleRegion? {
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset

    val nearLeft = fromScreenOffsetSync(Offset(left, bottom))
    val nearRight = fromScreenOffsetSync(Offset(right, bottom))
    val farLeft = fromScreenOffsetSync(Offset(left, top))
    val farRight = fromScreenOffsetSync(Offset(right, top))

    val corners = listOfNotNull(nearLeft, nearRight, farLeft, farRight)
    if (requireAllCorners && corners.size < 4) return null
    if (corners.isEmpty()) return null

    val bounds = GeoRectBounds().apply { corners.forEach { extend(it) } }
    return VisibleRegion(
        bounds = bounds,
        nearLeft = nearLeft,
        nearRight = nearRight,
        farLeft = farLeft,
        farRight = farRight,
    )
}

/**
 * ビューポートのピクセルサイズ。
 *
 * 既定では [MapViewHolderInterface.mapView] が [View] ならそこから解決する。まだ計測前
 * （幅か高さが 0）なら null。ネイティブビューを持たないプロバイダ（WebView ブリッジや
 * ビュー以外の描画面）はこれを上書きする。
 */
fun MapViewHolderInterface<*, *>.viewportSizePx(): Size? {
    val view = mapView as? View ?: return null
    if (view.width <= 0 || view.height <= 0) return null
    return Size(view.width.toFloat(), view.height.toFloat())
}
