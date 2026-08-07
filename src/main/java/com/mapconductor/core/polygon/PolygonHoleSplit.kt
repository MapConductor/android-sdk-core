package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPointInterface

/**
 * 外周リング + 穴リング群を、穴のない「単純なリング」の集合へ分割する（分割方式）。
 *
 * 各穴について、穴の最上端頂点と最下端頂点から水平方向（東または西）へレイを飛ばし、
 * 外周（または分割済みピース境界）との最初の交点へ 2 本の「橋」を張る。2 本の橋で
 * 環状領域が 2 つの単純ポリゴンに分かれるため、keyhole（幅ゼロの切れ込み）や自己接触が
 * 一切生じない。HERE のように穴（innerBoundaries）を正しく抜けず、keyhole リングも
 * 塗れないレンダラ向け。
 *
 * - 橋の方向は交点までの経度差が小さい側を選ぶ（経度 180° 超のエッジはレンダラが
 *   対蹠線越えとして描くため、世界マスク級の外周でも橋が短辺側に張られる）。
 * - 橋レイが他の穴を横切る場合は反対方向を試し、それも不可なら当該穴のみ
 *   [bridgeHolesIntoSingleRingWrapAware]（keyhole）にフォールバックする。
 * - 入力リングの巻き方向は問わない。出力リングはすべて CCW・開リング。
 *
 * ios-sdk の `splitPolygonWithHolesIntoSimpleRings` の移植。
 */
fun splitPolygonWithHolesIntoSimpleRings(
    outer: List<GeoPointInterface>,
    holes: List<List<GeoPointInterface>>,
): List<List<GeoPointInterface>> {
    val outerOpen = dropClosingPoint(outer)
    if (outerOpen.size < 3) return emptyList()
    val pieces = mutableListOf(ensureCounterClockwise(outerOpen))
    val cleanHoles = holes.map { dropClosingPoint(it) }.filter { it.size >= 3 }
    if (cleanHoles.isEmpty()) return pieces

    for ((holeIndex, hole) in cleanHoles.withIndex()) {
        val probe = hole.firstOrNull() ?: continue
        val pieceIndex =
            pieces.indexOfFirst { evenOddContains(it, probe.latitude, probe.longitude) }
        if (pieceIndex < 0) continue

        val otherHoles =
            cleanHoles.filterIndexed { index, _ -> index != holeIndex }
        val split = splitPiece(pieces[pieceIndex], hole, otherHoles)
        if (split != null) {
            pieces.removeAt(pieceIndex)
            pieces.add(ensureCounterClockwise(split.first))
            pieces.add(ensureCounterClockwise(split.second))
        } else {
            // フォールバック: この穴だけ keyhole ブリッジ（微小開き）で抜く。
            val bridged =
                bridgeHolesIntoSingleRingWrapAware(
                    outer = pieces[pieceIndex],
                    holes = listOf(hole),
                    separation = 1e-6,
                )
            pieces[pieceIndex] = ensureCounterClockwise(bridged)
        }
    }
    return pieces
}
