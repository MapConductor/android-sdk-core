package com.mapconductor.core.marker

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPointInterface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * マーカーの帯の中での描く順。どのドライバーもこれで並べる。
 *
 * - 第 1 キーは `zIndex`（未指定は 0）。大きいほど手前。
 * - 同じ `zIndex` なら、**地図の向きに合わせた画面で下にあるほど**手前。
 *   画面上の高さも同じなら左ほど手前（北が上なら南ほど・西ほど手前）。
 *
 * 2 つ目は Google Maps JS の既定（画面で下のマーカーが手前、回転するとその場で
 * 入れ替わる）に合わせている。ただし Google は高さが同じとき後から足した方を
 * 手前にするが、ここでは左を手前にして、追加した順には左右させない。
 *
 * 返す値は `zIndex + 端数`。端数は [0, 0.5) に収めるので、`zIndex` の大小を
 * 決してひっくり返さない。以前は未指定のときだけ `-緯度 × 10^6 - 経度` を
 * 使っていたため、次の 2 つが起きていた。
 *
 * - 北半球（東京で約 -3.57e7）では、Google Maps が明示の `zIndex = 1` を
 *   それより奥に描いた（大きすぎる値を正しく比べない。実機で確認）。
 * - 南半球では値が大きな正になり、明示の小さな `zIndex` を追い越した。
 *
 * 値を小さく保つのはそのため。
 *
 * 向きは [Frame] で渡す。省略すると北が上（[Frame.NorthUp]）。**タイルに焼き込む
 * マーカーは常に北が上で並べる**（タイルは北が上で描くので、回転のたびに描き直す
 * ことになるため）。
 */
@InternalMapConductorApi
object MarkerDrawOrder {
    /** 端数の上限。0.5 未満にして、隣の `zIndex` と重ならないようにする。 */
    private const val TIEBREAK_SPAN = 0.45

    /** 左右の寄与。画面上の高さが同じときだけ効くよう、十分小さくする。 */
    private const val LEFT_WEIGHT = 1e-9

    private const val MAX_MERCATOR_LATITUDE = 85.05112878

    /** 並べ直しに値する向きの変化（度）。 */
    private const val HEADING_STEP = 0.5

    /** 並べ直しに値する基準経度の変化（度）。ずれが効くのは基準の裏側（±180°）だけ。 */
    private const val ORIGIN_STEP = 90.0

    /**
     * 並べる向き。
     *
     * @property headingDegrees カメラが向いている方位（heading。時計回り、度。90 なら
     *   画面の上が東）。MapConductor の `bearing` とは符号が逆なので
     *   [com.mapconductor.core.map.CameraBearing.toNativeHeading] で直して渡す。
     * @property originLongitude 経度の折り返しの基準。カメラの中心の経度を渡すと、
     *   日付変更線をまたいで見ていても正しく並ぶ。
     */
    data class Frame(
        val headingDegrees: Double = 0.0,
        val originLongitude: Double = 0.0,
    ) {
        /** [other] から変わったとき、並べ直す必要があるか。 */
        fun differsEnoughFrom(other: Frame): Boolean =
            angleBetween(headingDegrees, other.headingDegrees) >= HEADING_STEP ||
                angleBetween(originLongitude, other.originLongitude) >= ORIGIN_STEP

        companion object {
            val NorthUp = Frame()
        }
    }

    /**
     * 描く順の値。大きいほど手前。
     *
     * Float にして渡す SDK（Google Maps）でも、`zIndex` が数百までなら数十メートルの
     * 差を見分けられる。
     */
    fun of(
        zIndex: Int?,
        position: GeoPointInterface,
        headingDegrees: Double = 0.0,
        originLongitude: Double = 0.0,
    ): Double = (zIndex ?: 0) + tiebreak(position, headingDegrees, originLongitude)

    fun of(
        zIndex: Int?,
        position: GeoPointInterface,
        frame: Frame,
    ): Double = of(zIndex, position, frame.headingDegrees, frame.originLongitude)

    /**
     * 整数しか受けない SDK 向け（HERE の `drawOrder` など）。
     *
     * `zIndex × [INT_STRIDE] + 端数を [INT_STRIDE] 段に刻んだ値`。`zIndex` は
     * ±(Int.MAX / [INT_STRIDE]) の範囲に収めること。
     */
    fun ofInt(
        zIndex: Int?,
        position: GeoPointInterface,
        headingDegrees: Double = 0.0,
        originLongitude: Double = 0.0,
    ): Int {
        val primary = (zIndex ?: 0).coerceIn(-MAX_INT_Z_INDEX, MAX_INT_Z_INDEX)
        val step = (tiebreak(position, headingDegrees, originLongitude) / TIEBREAK_SPAN * (INT_STRIDE - 1)).toInt()
        return primary * INT_STRIDE + step
    }

    fun ofInt(
        zIndex: Int?,
        position: GeoPointInterface,
        frame: Frame,
    ): Int = ofInt(zIndex, position, frame.headingDegrees, frame.originLongitude)

    /** [ofInt] で 1 つの `zIndex` に割り当てる段の数。赤道でおよそ 60 m 刻み。 */
    const val INT_STRIDE: Int = 1_000_000

    /** [ofInt] が扱える `zIndex` の絶対値。 */
    const val MAX_INT_Z_INDEX: Int = Int.MAX_VALUE / INT_STRIDE - 1

    private fun tiebreak(
        position: GeoPointInterface,
        headingDegrees: Double,
        originLongitude: Double,
    ): Double {
        // Web Mercator の世界座標 [0, 1]。y は北が 0、南が 1。
        val latitude = position.latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
        val wx = (position.longitude + 180.0) / 360.0
        val sinLat = sin(latitude * PI / 180.0)
        val wy = 0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)
        // 基準からの差。経度は [-0.5, 0.5) に折り返す。
        val dx = wrap(wx - (originLongitude + 180.0) / 360.0)
        val dy = wy - 0.5
        // 画面の下向き・右向きの成分（カメラは北から時計回りに heading だけ向いている）。
        val theta = headingDegrees * PI / 180.0
        val down = -dx * sin(theta) + dy * cos(theta)
        val right = dx * cos(theta) + dy * sin(theta)
        // どちらも [0, 1] に収める（|dx|, |dy| ≤ 0.5 なので回しても √2 / 2 以内）。
        val down01 = (0.5 + down / SQRT2).coerceIn(0.0, 1.0)
        val left01 = (0.5 - right / SQRT2).coerceIn(0.0, 1.0)
        val share = down01 * (1.0 - LEFT_WEIGHT) + left01 * LEFT_WEIGHT
        // 0.5 に届かないよう、端の 1.0 は上限の少し手前に寄せる。
        return (TIEBREAK_SPAN * share).coerceAtMost(TIEBREAK_SPAN * (1.0 - 1e-12))
    }

    private val SQRT2 = sqrt(2.0)

    private fun wrap(value: Double): Double = value - floor(value + 0.5)

    private fun angleBetween(
        a: Double,
        b: Double,
    ): Double {
        val d = abs(((a - b) % 360.0 + 360.0) % 360.0)
        return if (d > 180.0) 360.0 - d else d
    }
}
