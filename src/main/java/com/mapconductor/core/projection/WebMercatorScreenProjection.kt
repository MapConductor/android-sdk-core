package com.mapconductor.core.projection

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.CameraBearing
import com.mapconductor.core.map.MapCameraPosition
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * 地理座標 ⇄ 画面座標を、カメラとビューの大きさだけから計算する。
 *
 * ## なぜコアに置くのか
 *
 * 地図が Web Mercator なら、投影はカメラ（中心・ズーム・方位）とビューの大きさで
 * 決まる。地図SDKに聞く必要がない。**WebView 系のプロバイダは同期の投影 API を
 * 持たない**（Longdo / MapTiler のホルダーは `toScreenOffset` が null）ため、
 * これが無いと InfoBubble・マーカー追従・タイルの当たり判定が黙って死ぬ。
 *
 * 同じ式を各プロバイダやブリッジ層で書き直すと、片方だけ直る／片方だけずれる。
 * **判定も計算もここに一本化すること。**
 *
 * ## 使えない条件
 *
 * - **Web Mercator でない地図**（3D globe の HERE、球面の Cesium 等）。
 *   これらは地図SDK自身の投影を使うこと。
 *
 * bearing（回転）に対応している。tilt は、カメラの上下視野角（`fovDegrees`）を渡したときだけ
 * 透視（ピンホールカメラ）で扱う。MapLibre GL JS と同じカメラ模型で、画面中央の 1px が
 * 世界の 1px（`256 * 2^zoom`）になり、カメラは中央の地点から焦点距離
 * `(高さ / 2) / tan(fov / 2)` だけ離れて tilt 分だけ傾いている。視野角を渡さない呼び出しは
 * 従来どおり平面の相似変換で、tilt があると奥ほどずれる（tilt 60 で 250px 近く）。
 */
object WebMercatorScreenProjection {
    /**
     * 統一ズーム 0 のときの世界の大きさ。統一ズームは Google 基準の 256px タイル。
     *
     * **この 256 は密度非依存の単位（dp / CSS ピクセル）。** 端末ピクセルで扱いたい
     * 呼び出し側は、大きさを dp で渡して結果に density を掛けること
     * （`LongdoMapViewHolder` がその例）。混ぜるとマーカーと吹き出しがずれる。
     */
    private const val WORLD_SIZE_AT_ZOOM_0 = 256.0

    /**
     * 地理座標 → 画面座標。ビューが未レイアウト（幅か高さが 0）なら null。
     *
     * @param widthPx ビューの幅。単位は [WORLD_SIZE_AT_ZOOM_0] と揃えること（dp）。
     * @param heightPx ビューの高さ。同上。戻り値も同じ単位。
     */
    fun toScreenOffset(
        position: GeoPointInterface,
        camera: MapCameraPosition,
        widthPx: Float,
        heightPx: Float,
    ): Offset? = toScreenOffset(position, camera, widthPx, heightPx, fovDegrees = null)

    /**
     * [toScreenOffset] の tilt 対応版。[fovDegrees] はカメラの上下視野角（地図エンジンの値）。
     * null か 0 以下、または tilt が 0 なら平面の式と同じ結果になる。カメラの背後や
     * 地平線より向こうに当たる点は null。
     */
    fun toScreenOffset(
        position: GeoPointInterface,
        camera: MapCameraPosition,
        widthPx: Float,
        heightPx: Float,
        fovDegrees: Double?,
    ): Offset? {
        if (widthPx <= 0f || heightPx <= 0f) return null
        val worldSize = WORLD_SIZE_AT_ZOOM_0 * 2.0.pow(camera.zoom)
        val center = normalize(camera.position)
        val target = normalize(position)

        // 日付変更線をまたぐときは短いほうへ回す。これをしないと地図の反対側へ飛ぶ。
        var dx = target.x - center.x
        if (dx > 0.5) dx -= 1.0
        if (dx < -0.5) dx += 1.0

        var sx = dx * worldSize
        var sy = (target.y - center.y) * worldSize
        if (camera.bearing != 0.0) {
            // 画面の上が指す方位はカメラの heading（= bearing の符号反転）。世界を
            // -heading 回すと、その方位が画面の上（-y）に来る。
            val angle = -CameraBearing.toNativeHeading(camera.bearing) * Math.PI / 180.0
            val rx = sx * cos(angle) - sy * sin(angle)
            val ry = sx * sin(angle) + sy * cos(angle)
            sx = rx
            sy = ry
        }
        val distance = cameraDistance(camera, heightPx, fovDegrees)
        if (distance != null) {
            // 地面上で中央から sy 手前（画面の下向き）の点。カメラは中央から distance 離れて
            // tilt だけ傾いているので、奥行きは distance - sy·sin(tilt)。
            val tilt = camera.tilt * Math.PI / 180.0
            val depth = distance - sy * sin(tilt)
            if (depth <= 0.0) return null
            val scale = distance / depth
            sx *= scale
            sy *= cos(tilt) * scale
        }
        val x = widthPx / 2.0 + sx
        val y = heightPx / 2.0 + sy
        if (!x.isFinite() || !y.isFinite()) return null
        return Offset(x.toFloat(), y.toFloat())
    }

    /** [toScreenOffset] の逆。画面座標 → 地理座標。タップの当たり判定に使う。 */
    fun fromScreenOffset(
        offset: Offset,
        camera: MapCameraPosition,
        widthPx: Float,
        heightPx: Float,
    ): GeoPoint? = fromScreenOffset(offset, camera, widthPx, heightPx, fovDegrees = null)

    /** tilt 対応版の [toScreenOffset] の逆。地平線より上の点は null。 */
    fun fromScreenOffset(
        offset: Offset,
        camera: MapCameraPosition,
        widthPx: Float,
        heightPx: Float,
        fovDegrees: Double?,
    ): GeoPoint? {
        if (widthPx <= 0f || heightPx <= 0f) return null
        var sx = offset.x - widthPx / 2.0
        var sy = offset.y - heightPx / 2.0
        val distance = cameraDistance(camera, heightPx, fovDegrees)
        if (distance != null) {
            // toScreenOffset の透視を外す: y = D·g·cos t / (D − g·sin t) を g について解く。
            val tilt = camera.tilt * Math.PI / 180.0
            val denominator = distance * cos(tilt) + sy * sin(tilt)
            if (denominator <= 0.0) return null
            val ground = sy * distance / denominator
            sx *= (distance - ground * sin(tilt)) / distance
            sy = ground
        }
        if (camera.bearing != 0.0) {
            val angle = CameraBearing.toNativeHeading(camera.bearing) * Math.PI / 180.0
            val rx = sx * cos(angle) - sy * sin(angle)
            val ry = sx * sin(angle) + sy * cos(angle)
            sx = rx
            sy = ry
        }
        val worldSize = WORLD_SIZE_AT_ZOOM_0 * 2.0.pow(camera.zoom)
        val center = normalize(camera.position)
        var wx = center.x + sx / worldSize
        val wy = center.y + sy / worldSize
        wx -= Math.floor(wx)

        val projected =
            ProjectedPoint(
                (wx - 0.5) * 2.0 * WEB_MERCATOR_MAX_EXTENT_METERS,
                (0.5 - wy) * 2.0 * WEB_MERCATOR_MAX_EXTENT_METERS,
            )
        val point = WebMercator.unproject(projected)
        if (!point.latitude.isFinite() || !point.longitude.isFinite()) return null
        return GeoPoint(latitude = point.latitude, longitude = point.longitude, altitude = 0.0)
    }

    /**
     * 画面中央の地点からカメラまでの距離（[heightPx] と同じ単位）。tilt が無いか視野角が
     * 分からなければ null（平面の式を使う）。
     */
    private fun cameraDistance(
        camera: MapCameraPosition,
        heightPx: Float,
        fovDegrees: Double?,
    ): Double? {
        if (fovDegrees == null || fovDegrees <= 0.0 || camera.tilt == 0.0) return null
        val halfFov = fovDegrees * Math.PI / 360.0
        return heightPx / 2.0 / tan(halfFov)
    }

    /** Web Mercator のメートル座標を [0,1] へ。y は北が 0。 */
    private fun normalize(position: GeoPointInterface): Normalized {
        val projected = WebMercator.project(position)
        val extent = 2.0 * WEB_MERCATOR_MAX_EXTENT_METERS
        return Normalized(x = 0.5 + projected.x / extent, y = 0.5 - projected.y / extent)
    }

    private data class Normalized(
        val x: Double,
        val y: Double,
    )
}
