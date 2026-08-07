package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPointInterface
import kotlin.math.abs

/**
 * リング操作の小道具（重複除去、頂点探索、交差判定、内外判定）。
 *
 * すべて副作用のない計算。分割の各段から共通で呼ばれる。
 * ios-sdk の `PolygonHoleSplitHelpers.swift` の移植。
 */

internal fun dropClosingPoint(points: List<GeoPointInterface>): List<GeoPointInterface> {
    if (points.size >= 2) {
        val first = points.first()
        val last = points.last()
        if (first.latitude == last.latitude && first.longitude == last.longitude) {
            return points.dropLast(1)
        }
    }
    return points
}

/** 最上端（isTop）または最下端の頂点。緯度が同じ場合は経度が大きい（東の）方。 */
internal fun extremeVertexIndex(
    ring: List<GeoPointInterface>,
    isTop: Boolean,
): Int? {
    if (ring.isEmpty()) return null
    var best = 0
    for (index in ring.indices) {
        val candidate = ring[index]
        val current = ring[best]
        val better =
            if (candidate.latitude == current.latitude) {
                candidate.longitude > current.longitude
            } else if (isTop) {
                candidate.latitude > current.latitude
            } else {
                candidate.latitude < current.latitude
            }
        if (better) best = index
    }
    return best
}

/** リングのエッジと水平線 y=lat の交点のうち、x > fromLng で最小の x（最初の東向き交点）。 */
internal data class EastCrossing(
    val edgeIndex: Int,
    val x: Double,
    val t: Double,
)

internal fun firstEastCrossing(
    ring: List<GeoPointInterface>,
    fromLat: Double,
    fromLng: Double,
): EastCrossing? {
    var best: EastCrossing? = null
    for (index in ring.indices) {
        val a = ring[index]
        val b = ring[(index + 1) % ring.size]
        if ((a.latitude > fromLat) == (b.latitude > fromLat)) continue
        val t = (fromLat - a.latitude) / (b.latitude - a.latitude)
        val x = a.longitude + t * (b.longitude - a.longitude)
        if (x <= fromLng) continue
        if (best == null || x < best.x) {
            best = EastCrossing(index, x, t)
        }
    }
    return best
}

/** リングのエッジが水平セグメント（y=lat, x∈(x0,x1)）と交差するか。 */
internal fun horizontalSegmentIntersectsRing(
    ring: List<GeoPointInterface>,
    lat: Double,
    x0: Double,
    x1: Double,
    skipVertex: GeoPointInterface? = null,
): Boolean {
    val lo = minOf(x0, x1)
    val hi = maxOf(x0, x1)
    for (index in ring.indices) {
        val a = ring[index]
        val b = ring[(index + 1) % ring.size]
        if ((a.latitude > lat) == (b.latitude > lat)) continue
        val t = (lat - a.latitude) / (b.latitude - a.latitude)
        val x = a.longitude + t * (b.longitude - a.longitude)
        if (skipVertex != null && abs(x - skipVertex.longitude) < 1e-12) continue
        if (x > lo && x < hi) return true
    }
    return false
}

/** hole の from→to を配列順方向（インデックス増加、循環）で辿ったチェイン（両端含む）。 */
internal fun holeChain(
    hole: List<GeoPointInterface>,
    from: Int,
    to: Int,
): List<GeoPointInterface> {
    val chain = mutableListOf<GeoPointInterface>()
    var index = from
    while (true) {
        chain.add(hole[index])
        if (index == to) break
        index = (index + 1) % hole.size
    }
    return chain
}

internal fun meanLng(chain: List<GeoPointInterface>): Double {
    if (chain.size <= 2) {
        if (chain.isEmpty()) return Double.NEGATIVE_INFINITY
        return chain.sumOf { it.longitude } / chain.size
    }
    val interior = chain.subList(1, chain.size - 1)
    return interior.sumOf { it.longitude } / interior.size
}

/** ring の from→to を配列順方向（循環）で辿った列（両端含む）。 */
internal fun walkForward(
    ring: List<GeoPointInterface>,
    from: Int,
    to: Int,
): List<GeoPointInterface> {
    val result = mutableListOf<GeoPointInterface>()
    var index = from
    while (true) {
        result.add(ring[index])
        if (index == to) break
        index = (index + 1) % ring.size
    }
    return result
}

/** 連続する同一座標（長さゼロのエッジ）を除去する（先頭・末尾の一致も除く）。 */
internal fun dedupeConsecutive(ring: List<GeoPointInterface>): List<GeoPointInterface> {
    if (ring.isEmpty()) return ring
    val result = mutableListOf<GeoPointInterface>()
    for (point in ring) {
        val last = result.lastOrNull()
        if (last != null && last.latitude == point.latitude && last.longitude == point.longitude) {
            continue
        }
        result.add(point)
    }
    while (result.size >= 2 &&
        result.first().latitude == result.last().latitude &&
        result.first().longitude == result.last().longitude
    ) {
        result.removeAt(result.size - 1)
    }
    return result
}

/** 偶奇規則の内外判定。 */
internal fun evenOddContains(
    ring: List<GeoPointInterface>,
    lat: Double,
    lng: Double,
): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[j]
        if ((a.latitude > lat) != (b.latitude > lat)) {
            val x = a.longitude + ((lat - a.latitude) / (b.latitude - a.latitude)) * (b.longitude - a.longitude)
            if (lng < x) inside = !inside
        }
        j = i
    }
    return inside
}
