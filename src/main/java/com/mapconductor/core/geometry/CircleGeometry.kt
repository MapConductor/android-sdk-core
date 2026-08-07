package com.mapconductor.core.geometry

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.normalizeLng
import com.mapconductor.core.projection.Earth
import com.mapconductor.core.spherical.Spherical
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 円リング近似の既定分割数。 */
const val DEFAULT_CIRCLE_SEGMENTS = 128

/**
 * 円を頂点列（開いたリング）へ変換する共通ジオメトリ。各ドライバーはこの結果を
 * 自 SDK の型へ変換して描画するだけにする（円の形状定義を全プロバイダで統一する）。
 *
 * - geodesic=true : 球面上で中心から等距離のリング（[Spherical.computeOffset]）。
 * - geodesic=false: 中心緯度の局所平面（正距円筒）近似での等距離リング。
 *   小さな半径・低〜中緯度では geodesic とほぼ一致する。
 *
 * 経度は中心経度まわりに連続化（unwrap）して返す。±180 を跨ぐ円でも経度が飛ばないため、
 * 範囲外経度を扱える GL 系 SDK（Mapbox/MapLibre/MapTiler/Longdo）はこのまま 1 枚の
 * ポリゴンとして描画できる（子午線の継ぎ目が出ない）。経度 ±180 に制約のある SDK
 * （TomTom 等）は、各点を normalize してから [splitRingByMeridian] で分割すること。
 *
 * リングは閉じていない（必要なら [closeRing] を使う）。半径 0 以下・分割数 3 未満は
 * 空リストを返す。
 */
fun circleToRing(
    center: GeoPointInterface,
    radiusMeters: Double,
    geodesic: Boolean,
    segments: Int = DEFAULT_CIRCLE_SEGMENTS,
): List<GeoPointInterface> {
    if (radiusMeters <= 0.0 || segments < 3) return emptyList()
    if (geodesic) {
        return (0 until segments).map { i ->
            val p = Spherical.computeOffset(center, radiusMeters, 360.0 * i / segments)
            GeoPoint.fromLatLong(
                latitude = p.latitude,
                // 中心経度まわりに連続化する（computeOffset は [-180,180] へ正規化して返すため）。
                longitude = center.longitude + normalizeLng(p.longitude - center.longitude),
            )
        }
    }
    val metersPerDegree = Earth.CIRCUMFERENCE_METERS / 360.0
    // 極付近で経度補正が発散しないよう下限を設ける。
    val latCorrection = cos(Math.toRadians(center.latitude)).coerceAtLeast(1e-6)
    return (0 until segments).map { i ->
        val angle = 2.0 * PI * i / segments
        val deltaLat = radiusMeters / metersPerDegree * cos(angle)
        val deltaLng = radiusMeters / (metersPerDegree * latCorrection) * sin(angle)
        GeoPoint.fromLatLong(
            latitude = (center.latitude + deltaLat).coerceIn(-90.0, 90.0),
            longitude = center.longitude + deltaLng,
        )
    }
}
