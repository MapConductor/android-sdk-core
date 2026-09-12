package com.mapconductor.core.map

import com.mapconductor.core.InternalMapConductorApi

/**
 * [com.mapconductor.core.features.MapCameraPosition.bearing] とネイティブ SDK の回転値の変換。
 *
 * MapConductor の `bearing` は **「値を増やすと地図が時計回り（右）に回る」** 向きで定義する。
 * 0 が北で、90 なら地図全体が右へ 90 度回り、画面の上には西が来る。
 *
 * ネイティブ SDK は大きく 2 系統に分かれる。
 *
 * - **heading 系**（Google Maps / MapLibre / Mapbox / HERE / MapKit ほか）
 *   「画面の上が指す方位を北から時計回りに測る」＝カメラの向き。値を増やすと地図は
 *   反時計回りに回るので、MapConductor とは符号が反転する。[toNativeHeading]。
 * - **rotation 系**（ArcGIS の `Viewpoint.rotation` など）「北を時計回りに回す量」＝
 *   地図の回転角。MapConductor と同じ向きなので変換は恒等になる。[toNativeRotation]。
 *
 * 符号をプロバイダ各所に散らすと必ずどこかが漏れるので、変換はここだけに置く。
 */
@InternalMapConductorApi
object CameraBearing {

    /** 角度を 0 以上 360 未満へ畳む。 */
    fun normalizeDegrees360(degrees: Double): Double {
        if (!degrees.isFinite()) return 0.0
        val wrapped = degrees % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }

    /**
     * MapConductor の bearing → heading 系ネイティブ SDK の値。
     *
     * カメラが向いている方位でもあるので、負 tilt エミュレーションで
     * 「カメラの前進方向」が要るときもこれを使う。
     */
    fun toNativeHeading(bearing: Double): Double = normalizeDegrees360(-bearing)

    /** heading 系ネイティブ SDK の値 → MapConductor の bearing。 */
    fun bearingFromNativeHeading(heading: Double): Double = normalizeDegrees360(-heading)

    /** MapConductor の bearing → rotation 系ネイティブ SDK の値（向きが同じなので恒等）。 */
    fun toNativeRotation(bearing: Double): Double = normalizeDegrees360(bearing)

    /** rotation 系ネイティブ SDK の値 → MapConductor の bearing（同上）。 */
    fun bearingFromNativeRotation(rotation: Double): Double = normalizeDegrees360(rotation)
}
