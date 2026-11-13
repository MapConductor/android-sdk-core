package com.mapconductor.core.projection

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * Maximum extent of the Web Mercator projection: half the equatorial
 * circumference (πa ≈ 20037508.34 m). Projected x/y values fall within
 * ±this value.
 */
const val WEB_MERCATOR_MAX_EXTENT_METERS: Double = PI * Earth.RADIUS_METERS

/**
 * 投影が定義される緯度の端。
 *
 * Web Mercator は極で発散するので、正方形のワールドに収まる範囲で切る。
 * 世界中のタイルサーバが使っている値で、ここが `MAX_EXTENT` と一致する緯度。
 */
const val WEB_MERCATOR_MAX_LATITUDE: Double = 85.05112878

object WebMercator : ProjectionInterface {
    /**
     * 緯度経度 → EPSG:3857 メートル。
     *
     * 入力は投影前に wrap とクランプを通す。通さないと `latitude = 90` で
     * `tan()` が発散し、呼び出し側に無限大が渡る。実測では web / android の
     * 旧実装が `+90` で 238107693（有限のゴミ）を、`-90` で `-inf` を返して
     * いた -- 同じ式が符号で違う壊れ方をしていた。ios-sdk は最初からクランプ
     * していたので、そちらに合わせてある。
     */
    override fun project(position: GeoPointInterface): ProjectedPoint {
        val wrapped = position.wrap()
        val latitude = wrapped.latitude.coerceIn(-WEB_MERCATOR_MAX_LATITUDE, WEB_MERCATOR_MAX_LATITUDE)
        val x = wrapped.longitude * WEB_MERCATOR_MAX_EXTENT_METERS / 180
        val y = ln(tan((90 + latitude) * PI / 360)) * WEB_MERCATOR_MAX_EXTENT_METERS / PI
        return ProjectedPoint(x, y)
    }

    /** EPSG:3857 メートル → 緯度経度。結果は wrap して返す。 */
    override fun unproject(point: ProjectedPoint): GeoPointInterface {
        val longitude = point.x * 180 / WEB_MERCATOR_MAX_EXTENT_METERS
        val latitude = 180 / PI * (2 * atan(exp(point.y * PI / WEB_MERCATOR_MAX_EXTENT_METERS)) - PI / 2)
        return GeoPoint(latitude, longitude, 0.0).wrap()
    }
}
