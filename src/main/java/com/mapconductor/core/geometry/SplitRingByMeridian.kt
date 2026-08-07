package com.mapconductor.core.geometry

import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.spherical.splitByMeridian
import kotlin.math.abs

/**
 * 閉じたリング（開いた頂点列として渡す）を ±180 子午線で分割する。
 *
 * [splitByMeridian] は開いたパス用で、末尾→先頭のラップセグメントを見ないため、
 * 子午線を偶数回跨ぐリングでは「最初の断片」と「最後の断片」が本来ひとつながりの
 * ピースなのに別々に閉じられ、隙間（くさび）が生じる。ここでは最初の交差の直後から
 * 始まるようにリングを回転させ、ラップセグメントも含めて分割したうえで、先頭と末尾の
 * 断片を結合して正しいピース分割を返す。
 *
 * 交差が無ければ入力リングをそのまま 1 断片として返す。
 */
fun splitRingByMeridian(
    ring: List<GeoPointInterface>,
    geodesic: Boolean,
): List<List<GeoPointInterface>> {
    if (ring.size < 3) return if (ring.isEmpty()) emptyList() else listOf(ring)

    fun crossesAt(i: Int): Boolean {
        val a = ring[i]
        val b = ring[(i + 1) % ring.size]
        return abs(b.longitude - a.longitude) > 180.0
    }

    val firstCrossing = ring.indices.firstOrNull { crossesAt(it) } ?: return listOf(ring)

    // 最初の交差セグメント p[k] -> p[k+1] の直後（p[k+1]）から始まるよう回転し、
    // 末尾に先頭点を足してラップセグメント（p[k] -> p[k+1]）も処理対象にする。
    val rotated =
        ring.subList(firstCrossing + 1, ring.size) + ring.subList(0, firstCrossing + 1)
    val fragments = splitByMeridian(rotated + rotated.first(), geodesic)
    if (fragments.size < 2) return fragments

    // 末尾断片はラップ交差で始まった「先頭断片の続き」（同じ点から始まる）なので結合する。
    val merged = fragments.last() + fragments.first().drop(1)
    return listOf(merged) + fragments.subList(1, fragments.size - 1)
}
