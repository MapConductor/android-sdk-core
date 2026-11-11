package com.mapconductor.core.marker

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPointInterface

/**
 * マーカーの帯の中での描く順。どのドライバーもこれで並べる。
 *
 * - 第 1 キーは `zIndex`（未指定は 0）。大きいほど手前。
 * - 同じ `zIndex` なら南（緯度が小さい）ほど手前、緯度も同じなら西ほど手前。
 *
 * 返す値は `zIndex + 端数`。端数は [0, 0.5) に収めるので、`zIndex` の大小を
 * 決してひっくり返さない。以前は未指定のときだけ `-緯度 × 10^6 - 経度` を
 * 使っていたため、次の 2 つが起きていた。
 *
 * - 北半球（東京で約 -3.57e7）では、Google Maps が明示の `zIndex = 1` を
 *   それより奥に描いた（大きすぎる値を正しく比べない。実機で確認）。
 * - 南半球では値が大きな正になり、明示の小さな `zIndex` を追い越した。
 *
 * 値を小さく保つのはそのため。追加した順には左右されない。
 */
@InternalMapConductorApi
object MarkerDrawOrder {
    /** 端数の上限。0.5 未満にして、隣の `zIndex` と重ならないようにする。 */
    private const val TIEBREAK_SPAN = 0.49

    /** 経度の寄与。緯度が同じときだけ効くよう、緯度の最小の差より十分小さくする。 */
    private const val LONGITUDE_WEIGHT = 1e-6

    /**
     * 描く順の値。大きいほど手前。
     *
     * Float にして渡す SDK（Google Maps）でも、`zIndex` が数百までなら緯度で
     * 数メートルの差を見分けられる。
     */
    fun of(
        zIndex: Int?,
        position: GeoPointInterface,
    ): Double = (zIndex ?: 0) + tiebreak(position)

    /**
     * 整数しか受けない SDK 向け（HERE の `drawOrder` など）。
     *
     * `zIndex × [INT_STRIDE] + 端数を [INT_STRIDE] 段に刻んだ値`。`zIndex` は
     * ±(Int.MAX / [INT_STRIDE]) の範囲に収めること。
     */
    fun ofInt(
        zIndex: Int?,
        position: GeoPointInterface,
    ): Int {
        val primary = (zIndex ?: 0).coerceIn(-MAX_INT_Z_INDEX, MAX_INT_Z_INDEX)
        val step = (tiebreak(position) / TIEBREAK_SPAN * (INT_STRIDE - 1)).toInt()
        return primary * INT_STRIDE + step
    }

    /** [ofInt] で 1 つの `zIndex` に割り当てる段の数。緯度でおよそ 1.8e-4 度刻み。 */
    const val INT_STRIDE: Int = 1_000_000

    /** [ofInt] が扱える `zIndex` の絶対値。 */
    const val MAX_INT_Z_INDEX: Int = Int.MAX_VALUE / INT_STRIDE - 1

    private fun tiebreak(position: GeoPointInterface): Double {
        val latitude = position.latitude.coerceIn(-90.0, 90.0)
        val longitude = position.longitude.coerceIn(-180.0, 180.0)
        // 南ほど大きく（0..1）、西ほど大きく（0..1）。
        val south = (90.0 - latitude) / 180.0
        val west = (180.0 - longitude) / 360.0
        return TIEBREAK_SPAN * ((south + west * LONGITUDE_WEIGHT) / (1.0 + LONGITUDE_WEIGHT))
    }
}
