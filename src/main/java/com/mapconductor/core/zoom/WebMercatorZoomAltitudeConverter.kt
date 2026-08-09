package com.mapconductor.core.zoom

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/**
 * Web Mercator 系の地図SDK向けの、統一ズーム ⇄ 高度の変換。
 *
 * 統一ズームは Google Maps 基準（256px タイル）。各 SDK のネイティブズームとの差は
 * [zoomOffsetAt] だけなので、そこをパラメータにして実装を 1 本にまとめてある。
 *
 * ```
 * unifiedZoom = nativeZoom + zoomOffsetAt(latitude)
 * distance    = zoom0Altitude * cos(latitude) / 2^unifiedZoom
 * altitude    = distance * cos(tilt)
 * ```
 *
 * ## 継承ではなく合成で選ぶこと
 *
 * これは [AbstractZoomAltitudeConverter] の「既定の実装」ではなく、**Web Mercator の
 * 参照実装**である。全プロバイダの親にしてはいけない。ズームが `2^n` のスケール則に
 * 載らない SDK（正距円筒タイル、WMTS / ArcGIS LOD のような離散 scale-set、屋内地図の
 * ローカル平面座標、3D globe）ではこの式自体が成立しない。そうした SDK は
 * [AbstractZoomAltitudeConverter] を直接実装する。
 *
 * 較正定数（[zoom0Altitude]）も**プロバイダが持つ**。同じプロバイダでもプラットフォームで
 * 値が違う実例がある（ArcGIS の zoom0Altitude は iOS 141,600,000 / Android・React
 * 136,500,000）。コアに固定しないこと。
 *
 * @param zoomOffset `unifiedZoom = nativeZoom + zoomOffset`。512px タイルのベクタ
 *   エンジン（MapLibre / Mapbox / MapTiler）は 1.0、256px 基準（Google Maps / Longdo）は 0.0。
 *   緯度に依存するプロバイダは [GroundScaleZoomAltitudeConverter] を使う。
 */
open class WebMercatorZoomAltitudeConverter(
    zoom0Altitude: Double = DEFAULT_ZOOM0_ALTITUDE,
    private val zoomOffset: Double = 0.0,
) : AbstractZoomAltitudeConverter(zoom0Altitude) {
    /**
     * ネイティブズームに足すと統一ズームになる量。既定は緯度によらず [zoomOffset]。
     *
     * グラウンドスケール基準の SDK は緯度に依存するのでここを上書きする
     * （[GroundScaleZoomAltitudeConverter] を参照）。
     */
    protected open fun zoomOffsetAt(latitude: Double): Double = zoomOffset

    /** ネイティブズーム → 統一ズーム（Google Maps 基準）。 */
    fun toUnifiedZoom(
        nativeZoom: Double,
        latitude: Double = 0.0,
    ): Double = (nativeZoom + zoomOffsetAt(latitude)).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)

    /** 統一ズーム（Google Maps 基準） → ネイティブズーム。 */
    fun toNativeZoom(
        unifiedZoom: Double,
        latitude: Double = 0.0,
    ): Double = (unifiedZoom - zoomOffsetAt(latitude)).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)

    /**
     * 緯度による水平スケール補正。極付近で発散しないよう緯度を ±85° に、
     * 係数を [MIN_COS_LAT] にクランプする。
     */
    protected fun cosLatitudeFactor(latitudeDeg: Double): Double {
        val clampedLat = latitudeDeg.coerceIn(-85.0, 85.0)
        return max(MIN_COS_LAT, abs(cos(Math.toRadians(clampedLat))))
    }

    /** 傾きによる視距離補正。真横（90°）で発散しないよう [MIN_COS_TILT] にクランプする。 */
    protected fun cosTiltFactor(tiltDeg: Double): Double {
        val clampedTilt = tiltDeg.coerceIn(0.0, 90.0)
        return max(MIN_COS_TILT, cos(Math.toRadians(clampedTilt)))
    }

    override fun zoomLevelToAltitude(
        zoomLevel: Double,
        latitude: Double,
        tilt: Double,
    ): Double {
        val unifiedZoom = toUnifiedZoom(zoomLevel, latitude)
        val distance = (zoom0Altitude * cosLatitudeFactor(latitude)) / ZOOM_FACTOR.pow(unifiedZoom)
        return (distance * cosTiltFactor(tilt)).coerceIn(MIN_ALTITUDE, MAX_ALTITUDE)
    }

    override fun altitudeToZoomLevel(
        altitude: Double,
        latitude: Double,
        tilt: Double,
    ): Double {
        val distance = altitude.coerceIn(MIN_ALTITUDE, MAX_ALTITUDE) / cosTiltFactor(tilt)
        val unifiedZoom = log2((zoom0Altitude * cosLatitudeFactor(latitude)) / distance)
        return toNativeZoom(unifiedZoom, latitude)
    }
}

/**
 * グラウンドスケール基準（画面上の meter/pixel が緯度によらず一定）でズームを定義する
 * SDK 向け。Web Mercator 基準との差が `log2(cos φ)` なので、そこだけを足す。
 *
 * @param baseZoomOffset 赤道でのオフセット。TomTom は 1.76（実測較正値）。
 */
open class GroundScaleZoomAltitudeConverter(
    zoom0Altitude: Double = DEFAULT_ZOOM0_ALTITUDE,
    private val baseZoomOffset: Double,
) : WebMercatorZoomAltitudeConverter(zoom0Altitude, baseZoomOffset) {
    override fun zoomOffsetAt(latitude: Double): Double = baseZoomOffset + log2(cosLatitudeFactor(latitude))
}
