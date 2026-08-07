package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import kotlin.math.abs

/**
 * 穴が 1 つのピースを、橋 2 本で 2 つの単純リングへ割る部分。
 *
 * まず東向きの橋を試し、駄目なら経度を鏡像反転して西向きで再試行する。
 * 反転して同じ関数を通すことで、東西で別実装を持たずに済む。
 * ios-sdk の `PolygonHolePieceSplit.swift` の移植。
 */

internal data class PieceSplitResult(
    val ringA: List<GeoPointInterface>,
    val ringB: List<GeoPointInterface>,
    val bridgeSpan: Double,
)

/**
 * piece（CCW・開リング）を hole で 2 つの単純リングへ分割する。
 * まず東向きの橋を試し、不可なら経度を鏡像反転して再試行（=西向き）。
 */
internal fun splitPiece(
    piece: List<GeoPointInterface>,
    hole: List<GeoPointInterface>,
    otherHoles: List<List<GeoPointInterface>>,
): Pair<List<GeoPointInterface>, List<GeoPointInterface>>? {
    val east = splitPieceEast(piece, hole, otherHoles)
    val westMirrored =
        splitPieceEast(
            piece.asReversed().map(::mirrorLngPoint),
            hole.asReversed().map(::mirrorLngPoint),
            otherHoles.map { it.map(::mirrorLngPoint) },
        )
    val west =
        westMirrored?.let {
            Pair(
                it.ringA.asReversed().map(::mirrorLngPoint),
                it.ringB.asReversed().map(::mirrorLngPoint),
            )
        }

    return when {
        east == null && west == null -> null
        west == null -> Pair(east!!.ringA, east.ringB)
        east == null -> west
        // どちらも可能なら橋の合計長（経度差）が短い方（描画エッジのラップ耐性が高い方）。
        east.bridgeSpan <= (westMirrored?.bridgeSpan ?: Double.POSITIVE_INFINITY) ->
            Pair(east.ringA, east.ringB)
        else -> west
    }
}

internal fun mirrorLngPoint(point: GeoPointInterface): GeoPointInterface =
    GeoPoint(
        latitude = point.latitude,
        longitude = -point.longitude,
        altitude = point.altitude ?: 0.0,
    )

/** 東向き（+x 方向）の橋 2 本で piece を分割する。piece は CCW・開リングであること。 */
internal fun splitPieceEast(
    piece: List<GeoPointInterface>,
    hole: List<GeoPointInterface>,
    otherHoles: List<List<GeoPointInterface>>,
): PieceSplitResult? {
    val ccwPiece = ensureCounterClockwise(piece)
    if (hole.size < 3) return null

    // 最上端・最下端の頂点（同緯度なら東寄りを選ぶ: レイが自分自身の水平エッジを掠らないように）。
    val v1Index = extremeVertexIndex(hole, isTop = true) ?: return null
    val v2Index = extremeVertexIndex(hole, isTop = false) ?: return null
    if (v1Index == v2Index) return null
    val v1 = hole[v1Index]
    val v2 = hole[v2Index]

    val c1 = firstEastCrossing(ccwPiece, v1.latitude, v1.longitude) ?: return null
    val c2 = firstEastCrossing(ccwPiece, v2.latitude, v2.longitude) ?: return null

    // 橋が他の穴を横切るなら不可。
    for (other in otherHoles) {
        if (horizontalSegmentIntersectsRing(other, v1.latitude, v1.longitude, c1.x) ||
            horizontalSegmentIntersectsRing(other, v2.latitude, v2.longitude, c2.x)
        ) {
            return null
        }
    }
    // 橋が自分の穴の他エッジを横切るケース（凹んだ穴）も不可。
    if (horizontalSegmentIntersectsRing(hole, v1.latitude, v1.longitude, c1.x, skipVertex = v1) ||
        horizontalSegmentIntersectsRing(hole, v2.latitude, v2.longitude, c2.x, skipVertex = v2)
    ) {
        return null
    }

    val p1 = GeoPoint(latitude = v1.latitude, longitude = c1.x)
    val p2 = GeoPoint(latitude = v2.latitude, longitude = c2.x)

    // 交点を piece のエッジへ挿入（同一エッジに複数挿入される場合はエッジ始点からの距離順）。
    val augmented = mutableListOf<GeoPointInterface>()
    var p1Position = -1
    var p2Position = -1
    for ((index, point) in ccwPiece.withIndex()) {
        augmented.add(point)
        val inserts = mutableListOf<Triple<Double, GeoPoint, Boolean>>()
        if (c1.edgeIndex == index) inserts.add(Triple(c1.t, p1, true))
        if (c2.edgeIndex == index) inserts.add(Triple(c2.t, p2, false))
        inserts.sortBy { it.first }
        for (insert in inserts) {
            augmented.add(insert.second)
            if (insert.third) {
                p1Position = augmented.size - 1
            } else {
                p2Position = augmented.size - 1
            }
        }
    }
    if (p1Position < 0 || p2Position < 0) return null

    // 穴の頂点列を v1→v2（配列順方向）と v2→v1（配列順方向）の 2 チェインに分ける。
    val chainF = holeChain(hole, v1Index, v2Index) // v1 → v2
    val chainG = holeChain(hole, v2Index, v1Index) // v2 → v1
    // 「東側チェイン」（v1→v2 向き）: 内部頂点の平均経度が大きい方。
    val eastChainV1toV2: List<GeoPointInterface>
    val westChainV2toV1: List<GeoPointInterface>
    if (meanLng(chainF) >= meanLng(chainG.asReversed())) {
        eastChainV1toV2 = chainF
        westChainV2toV1 = chainG
    } else {
        eastChainV1toV2 = chainG.asReversed()
        westChainV2toV1 = chainF.asReversed()
    }

    // ring A: p2 →(piece 順方向)→ p1 → v1 →(穴 東側)→ v2 → (p2 へ閉じる)
    val ringA = dedupeConsecutive(walkForward(augmented, p2Position, p1Position) + eastChainV1toV2)
    // ring B: p1 →(piece 順方向)→ p2 → v2 →(穴 西側)→ v1 → (p1 へ閉じる)
    val ringB = dedupeConsecutive(walkForward(augmented, p1Position, p2Position) + westChainV2toV1)

    if (ringA.size < 3 || ringB.size < 3) return null
    val span = abs(c1.x - v1.longitude) + abs(c2.x - v2.longitude)
    return PieceSplitResult(ringA, ringB, span)
}
