package com.mapconductor.core.geometry

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.normalize
import com.mapconductor.core.normalizeLng
import com.mapconductor.core.spherical.WGS84Geodesic
import com.mapconductor.core.spherical.Planar
import com.mapconductor.core.spherical.splitByMeridian

/*
 * ポリライン／ポリゴン描画用の共通ジオメトリパイプライン。
 *
 * 「geodesic に応じた補間 → 正規化 → 子午線分割 → 縮退リングの除去」までをプロバイダ非依存で行う。
 * 各ドライバーはこの結果を自 SDK の型（GeoJSON Feature 等）へ変換して描画するだけにする。
 */

/** geodesic に応じた補間で点列を密度化し、緯度経度を正規化して返す。 */
fun densifyAndNormalize(
    points: List<GeoPointInterface>,
    geodesic: Boolean,
): List<GeoPointInterface> =
    when (geodesic) {
        true -> WGS84Geodesic.createInterpolatePoints(points)
        false -> Planar.createInterpolatePoints(points)
    }.map { it.normalize() }

/**
 * ポリライン用パイプライン。密度化・正規化後に子午線で分割したセグメント列を返す。
 * 頂点 2 未満の入力、および分割で 2 点未満になったセグメントは除く（空リストになり得る）。
 */
fun buildPolylineSegments(
    points: List<GeoPointInterface>,
    geodesic: Boolean,
): List<List<GeoPointInterface>> {
    if (points.size < 2) return emptyList()
    return splitByMeridian(densifyAndNormalize(points, geodesic), geodesic)
        .filter { it.size >= 2 }
}

/**
 * ポリゴンの外周リング（子午線分割済み）と穴リング。リングは閉じていない
 * （末尾に先頭点を追加する処理は各ドライバーの座標変換後に行う）。
 */
class PolygonRings(
    val outerRings: List<List<GeoPointInterface>>,
    val holeRings: List<List<GeoPointInterface>>,
)

/**
 * ポリゴン用パイプライン。外周を密度化→分割し、穴も同じ方式で密度化する。
 *
 * 外周が子午線で複数リングに分割された場合、穴を分割後の各ピースへ再割当てできないため
 * 穴を含めない（従来から全 GeoJSON 系ドライバー共通の仕様）。頂点 3 未満の外周入力は
 * 空の結果を返し、3 点未満に縮退したリングは除外する。
 */
fun buildPolygonRings(
    points: List<GeoPointInterface>,
    holes: List<List<GeoPointInterface>>,
    geodesic: Boolean,
): PolygonRings {
    if (points.size < 3) return PolygonRings(emptyList(), emptyList())
    val outerRings =
        splitRingByMeridian(densifyAndNormalize(points, geodesic), geodesic)
            .filter { it.size >= 3 }
    val includeHoles = holes.isNotEmpty() && outerRings.size == 1
    val holeRings =
        if (includeHoles) {
            holes
                .filter { it.size >= 3 }
                .map { densifyAndNormalize(it, geodesic) }
                .filter { it.size >= 3 }
        } else {
            emptyList()
        }
    return PolygonRings(outerRings = outerRings, holeRings = holeRings)
}

/** リングが閉じていなければ先頭点を末尾へ追加して閉じる。 */
fun <T> closeRing(ring: List<T>): List<T> =
    if (ring.isNotEmpty() && ring.first() != ring.last()) ring + ring.first() else ring

// ─── unwrap 版パイプライン（±180 超の経度を扱える GL 系 SDK 向け） ─────────────
//
// Mapbox/MapLibre（および WebView の MapLibre GL JS = MapTiler/Longdo）は GeoJSON の
// 経度が [-180, 180] を超えていてもそのまま描画できる。経度を連続化（unwrap）した
// 単一ジオメトリを渡せば、±180 跨ぎでも分割不要で継ぎ目が出ず、外周が分割される
// ことによる「穴を含められない」制約も無くなる。経度 ±180 に制約のある SDK は
// 従来どおり [buildPolylineSegments]／[buildPolygonRings]（分割版）を使うこと。

/**
 * 密度化済みの点列の経度を、直前の点からの最短差分を積み上げる形で連続化する。
 * 先頭点は [anchorLng]（省略時は正規化した自身の経度）から ±180 以内に配置する。
 */
private fun unwrapContinuous(
    points: List<GeoPointInterface>,
    anchorLng: Double? = null,
): List<GeoPointInterface> {
    if (points.isEmpty()) return points
    val result = ArrayList<GeoPointInterface>(points.size)
    val first = points.first()
    var prevLng =
        if (anchorLng == null) {
            normalizeLng(first.longitude)
        } else {
            anchorLng + normalizeLng(first.longitude - anchorLng)
        }
    result.add(GeoPoint.fromLatLong(latitude = first.latitude, longitude = prevLng))
    for (i in 1 until points.size) {
        val p = points[i]
        prevLng += normalizeLng(p.longitude - points[i - 1].longitude)
        result.add(GeoPoint.fromLatLong(latitude = p.latitude, longitude = prevLng))
    }
    return result
}

/**
 * ポリライン用パイプライン（unwrap 版）。密度化後に経度を連続化した単一パスを返す。
 * 頂点 2 未満の入力は空リストを返す。
 */
fun buildUnwrappedPolylinePath(
    points: List<GeoPointInterface>,
    geodesic: Boolean,
): List<GeoPointInterface> {
    if (points.size < 2) return emptyList()
    return unwrapContinuous(densifyAndNormalize(points, geodesic))
}

/**
 * ポリゴン用パイプライン（unwrap 版）。外周・穴とも密度化し、外周の先頭経度を基準に
 * 同一の連続座標系へ unwrap する（±180 跨ぎでも常に外周 1 リング + 全穴を返せる）。
 * 頂点 3 未満の外周入力は空の結果を返し、3 点未満に縮退した穴は除外する。
 */
fun buildUnwrappedPolygonRings(
    points: List<GeoPointInterface>,
    holes: List<List<GeoPointInterface>>,
    geodesic: Boolean,
): PolygonRings {
    if (points.size < 3) return PolygonRings(emptyList(), emptyList())
    val outer = unwrapContinuous(densifyAndNormalize(points, geodesic))
    val anchor = outer.first().longitude
    val holeRings =
        holes
            .filter { it.size >= 3 }
            .map { unwrapContinuous(densifyAndNormalize(it, geodesic), anchorLng = anchor) }
            .filter { it.size >= 3 }
    return PolygonRings(outerRings = listOf(outer), holeRings = holeRings)
}
