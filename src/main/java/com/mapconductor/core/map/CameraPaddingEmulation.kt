package com.mapconductor.core.map

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.spherical.Spherical
import com.mapconductor.core.zoom.AbstractZoomAltitudeConverter
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * カメラのパディング（[MapCameraPositionInterface.paddings]）を持たない地図SDK向けに、
 * パディングをカメラ中心のずらしで再現する。
 *
 * パディングを付けたカメラは「パディングを除いた領域の中央に `position` を置く」。
 * その位置は画面中央から `dx = (left - right) / 2`、`dy = (top - bottom) / 2`
 * （dp、右・下が正）だけずれている。ここでは、その画面上のずれが地上で何メートルに
 * なるかを解き、ネイティブ SDK へ渡すカメラ中心を求める。逆向きも持つ。
 *
 * 視野角はプロバイダが [MapCameraPositionInterface.fov] として持つ値を渡す。
 * コアは視野角の固定値を持たない。
 *
 * - `fov > 0`: ピンホールカメラとして遠近込みで解く
 * - `fov == 0`: 遠近なしの傾き（縦だけ cos(tilt) 倍に縮む）として解く
 * - `fov == null`: 傾きを無視して tilt 0 として解く
 *
 * 単位: パディングと地図の高さは dp、ズームは統一ズーム（Google Maps 基準、256dp タイル）。
 */
@InternalMapConductorApi
object CameraPaddingEmulation {
    /** 視線が地平線に届かないよう、地面への角度をここで止める。 */
    private const val MAX_RAY_ANGLE_DEG = 89.0

    /**
     * [camera] のパディングを中心のずらしに置き換えた、ネイティブ SDK へ渡すカメラ。
     * 返すカメラの paddings は 0。パディングが無ければ [camera] をそのまま返す。
     *
     * @param viewportHeight 地図の高さ（dp）
     */
    fun toNativeCamera(
        camera: MapCameraPosition,
        fov: Double?,
        viewportHeight: Double,
    ): MapCameraPosition {
        if (camera.paddings.isZero()) return camera
        val center =
            centerForTarget(
                target = camera.position,
                zoom = camera.zoom,
                tilt = camera.tilt,
                heading = CameraBearing.toNativeHeading(camera.bearing),
                paddings = camera.paddings,
                fov = fov,
                viewportHeight = viewportHeight,
            )
        return camera.copy(position = center, paddings = MapPaddings.Zeros)
    }

    /**
     * [toNativeCamera] の逆。ネイティブ SDK から読んだカメラ（中心が画面中央）を、
     * [paddings] を付けたカメラ（position がパディング中央）に直す。fov も入れる。
     */
    fun fromNativeCamera(
        camera: MapCameraPosition,
        paddings: MapPaddingsInterface?,
        fov: Double?,
        viewportHeight: Double,
    ): MapCameraPosition {
        val effective = paddings ?: MapPaddings.Zeros
        if (effective.isZero()) return camera.copy(paddings = MapPaddings.Zeros, fov = fov)
        val target =
            targetForCenter(
                center = camera.position,
                zoom = camera.zoom,
                tilt = camera.tilt,
                heading = CameraBearing.toNativeHeading(camera.bearing),
                paddings = effective,
                fov = fov,
                viewportHeight = viewportHeight,
            )
        return camera.copy(position = target, paddings = effective, fov = fov)
    }

    private fun MapPaddingsInterface?.isZero(): Boolean =
        this == null || (top == 0.0 && left == 0.0 && bottom == 0.0 && right == 0.0)

    /**
     * パディング付きのカメラで [target] を見るために、ネイティブ SDK へ渡すカメラ中心。
     *
     * @param heading カメラの向き（北から時計回り、ネイティブ SDK の heading）。
     *   MapConductor の bearing からは [CameraBearing.toNativeHeading] で得る。
     */
    fun centerForTarget(
        target: GeoPointInterface,
        zoom: Double,
        tilt: Double,
        heading: Double,
        paddings: MapPaddingsInterface?,
        fov: Double?,
        viewportHeight: Double,
    ): GeoPoint {
        // ズームの縮尺はカメラ中心の緯度で決まるが、中心はまだ分からない。target の緯度で
        // 一度解き、求めた中心の緯度で解き直す（差は数十 cm 以下になる）。
        var center: GeoPoint = GeoPoint.from(target)
        repeat(2) {
            val (forward, right) =
                groundOffset(paddings, zoom, center.latitude, tilt, fov, viewportHeight)
                    ?: return GeoPoint.from(target)
            // target は中心から (forward, right) にある。中心はその逆。
            center = move(target, -forward, -right, heading)
        }
        return center
    }

    /** [centerForTarget] の逆。ネイティブ SDK のカメラ中心から、パディング付きの位置を求める。 */
    fun targetForCenter(
        center: GeoPointInterface,
        zoom: Double,
        tilt: Double,
        heading: Double,
        paddings: MapPaddingsInterface?,
        fov: Double?,
        viewportHeight: Double,
    ): GeoPoint {
        val (forward, right) =
            groundOffset(paddings, zoom, center.latitude, tilt, fov, viewportHeight)
                ?: return GeoPoint.from(center)
        return move(center, forward, right, heading)
    }

    /**
     * 画面中央からパディング中央へのずれを、地上の (前方, 右) メートルで返す。
     * 前方はカメラの向き。ずれが無ければ null。
     */
    internal fun groundOffset(
        paddings: MapPaddingsInterface?,
        zoom: Double,
        latitude: Double,
        tilt: Double,
        fov: Double?,
        viewportHeight: Double,
    ): Pair<Double, Double>? {
        if (paddings == null) return null
        val dx = (paddings.left - paddings.right) / 2.0
        val dy = (paddings.top - paddings.bottom) / 2.0
        if (abs(dx) < 1e-9 && abs(dy) < 1e-9) return null

        val metersPerDp =
            AbstractZoomAltitudeConverter.WEB_MERCATOR_INITIAL_MPP_256 *
                cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0))) / 2.0.pow(zoom)
        val t = Math.toRadians(tilt.coerceIn(0.0, MAX_RAY_ANGLE_DEG))

        // 画面の下向き (dy > 0) は地上では手前（前方の負）。
        if (fov == null || t == 0.0 || viewportHeight <= 0.0) {
            return Pair(-dy * metersPerDp, dx * metersPerDp)
        }
        if (fov <= 0.0) {
            return Pair(-dy * metersPerDp / max(cos(t), 0.05), dx * metersPerDp)
        }

        val f = (viewportHeight / 2.0) / tan(Math.toRadians(fov) / 2.0)
        val distance = f * metersPerDp
        val height = distance * cos(t)
        val alpha = atan(dy / f)
        val ray = min(t - alpha, Math.toRadians(MAX_RAY_ANGLE_DEG))
        // カメラ直下から見た、パディング中央の地点と画面中央の地点までの水平距離の差。
        val back = distance * sin(t) - height * tan(ray)
        // 光軸方向の奥行きに比例して横の 1dp が伸びる。
        val depth = height / cos(ray) * cos(alpha)
        val right = dx * depth / f
        return Pair(-back, right)
    }

    private fun move(
        origin: GeoPointInterface,
        forward: Double,
        right: Double,
        heading: Double,
    ): GeoPoint {
        val distance = hypot(forward, right)
        if (distance < 1e-9) return GeoPoint.from(origin)
        val direction = heading + Math.toDegrees(atan2(right, forward))
        val moved = Spherical.computeOffset(origin, distance, direction)
        return GeoPoint(latitude = moved.latitude, longitude = moved.longitude, altitude = origin.altitude ?: 0.0)
    }
}
