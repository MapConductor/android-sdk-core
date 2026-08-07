package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPointInterface

/**
 * 靴ひも公式でリングの符号付き面積を返す（x=経度, y=緯度）。
 * 正なら反時計回り（CCW）、負なら時計回り（CW）。
 * 開リング・閉リング（先頭と末尾が同一）のどちらでも正しく動く。
 *
 * ios-sdk の `polygonSignedArea` と同じ契約。
 */
fun polygonSignedArea(ring: List<GeoPointInterface>): Double {
    val n = ring.size
    if (n < 3) return 0.0
    var sum = 0.0
    for (i in 0 until n) {
        val a = ring[i]
        val b = ring[(i + 1) % n]
        sum += (a.longitude * b.latitude) - (b.longitude * a.latitude)
    }
    return sum / 2.0
}

/** リングを CCW に揃える（必要なら反転）。ios-sdk の `ensureCounterClockwise` と同じ。 */
fun ensureCounterClockwise(ring: List<GeoPointInterface>): List<GeoPointInterface> =
    if (polygonSignedArea(ring) >= 0) ring else ring.asReversed()

/** リングを CW に揃える（必要なら反転）。ios-sdk の `ensureClockwiseRing` と同じ。 */
fun ensureClockwiseRing(ring: List<GeoPointInterface>): List<GeoPointInterface> =
    if (polygonSignedArea(ring) <= 0) ring else ring.asReversed()
