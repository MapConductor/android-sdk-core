package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPointInterface

/**
 * 外周リング + 複数の穴リングを、穴をブリッジで繋いだ「単一リング」に変換する
 * （mapbox/earcut の eliminateHoles 相当）。
 *
 * 穴（inner ring）をネイティブにサポートしない描画系（TomTom の通常 Polygon など）で、
 * 穴付きポリゴンを 1 枚の通常ポリゴンとして塗るために使う。各穴は外周（および既にブリッジ
 * 済みのリング）へ「ゼロ幅の橋」で接続され、全体で 1 つの弱単純ポリゴンになる。塗りは
 * 正しく穴が抜けるが、橋の部分は細い切れ込みとして残るため、輪郭線は別途 stroke-only の
 * ポリゴンで描くこと。
 *
 * 入力の巻き方向は問わない（内部で外周 CCW / 穴 CW に正規化する）。x=経度, y=緯度で扱う。
 */
fun bridgeHolesIntoSingleRing(
    outer: List<GeoPointInterface>,
    holes: List<List<GeoPointInterface>>,
): List<GeoPointInterface> {
    if (holes.isEmpty()) return outer
    var outerNode = buildRing(dropClosing(outer), wantClockwise = false) ?: return outer

    val queue = ArrayList<Node>()
    for (hole in holes) {
        val list = buildRing(dropClosing(hole), wantClockwise = true) ?: continue
        queue.add(leftmost(list))
    }
    // 穴を左端 x の昇順で処理する（earcut と同じ）。
    queue.sortWith(compareBy({ it.x }, { it.y }))

    for (holeLeftmost in queue) {
        val bridge = findHoleBridge(holeLeftmost, outerNode)
        if (bridge != null) {
            splitPolygon(bridge, holeLeftmost)
        }
    }

    val result = ArrayList<GeoPointInterface>(outer.size + holes.sumOf { it.size } * 2)
    var p = outerNode
    do {
        result.add(p.source)
        p = p.next
    } while (p !== outerNode)
    return result
}

private class Node(
    val x: Double,
    val y: Double,
    val source: GeoPointInterface,
) {
    var prev: Node = this
    var next: Node = this
}

private fun dropClosing(points: List<GeoPointInterface>): List<GeoPointInterface> =
    if (points.size >= 2 &&
        points.first().latitude == points.last().latitude &&
        points.first().longitude == points.last().longitude
    ) {
        points.dropLast(1)
    } else {
        points
    }

/** points から循環連結リストを構築し、head ノードを返す。wantClockwise に合わせて巻き方向を正規化。 */
private fun buildRing(
    points: List<GeoPointInterface>,
    wantClockwise: Boolean,
): Node? {
    if (points.size < 3) return null
    var sum = 0.0
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        sum += a.longitude * b.latitude - b.longitude * a.latitude
    }
    val isCcw = sum > 0.0
    // wantClockwise==true なら CW に、false なら CCW にしたい。現状と一致しなければ反転。
    val ordered = if (isCcw == wantClockwise) points.asReversed() else points

    var last: Node? = null
    for (pt in ordered) {
        val node = Node(pt.longitude, pt.latitude, pt)
        val prev = last
        if (prev == null) {
            node.prev = node
            node.next = node
        } else {
            node.prev = prev
            node.next = prev.next
            prev.next.prev = node
            prev.next = node
        }
        last = node
    }
    return last?.next
}

private fun leftmost(start: Node): Node {
    var p = start
    var min = start
    do {
        if (p.x < min.x || (p.x == min.x && p.y < min.y)) min = p
        p = p.next
    } while (p !== start)
    return min
}

/** 穴の左端ノードから見て、外周リング上に接続可能な（可視な）橋の相手ノードを探す。 */
private fun findHoleBridge(
    hole: Node,
    outerNode: Node,
): Node? {
    var p = outerNode
    val hx = hole.x
    val hy = hole.y
    var qx = Double.NEGATIVE_INFINITY
    var m: Node? = null

    // 穴左端から左向きに水平レイを飛ばし、交差する外周エッジのうち最も近い（x が最大の）ものを選ぶ。
    do {
        if (hy <= p.y && hy >= p.next.y && p.next.y != p.y) {
            val x = p.x + (hy - p.y) * (p.next.x - p.x) / (p.next.y - p.y)
            if (x <= hx && x > qx) {
                qx = x
                if (x == hx) {
                    return if (p.x < p.next.x) p else p.next
                }
                m = if (p.x < p.next.x) p else p.next
            }
        }
        p = p.next
    } while (p !== outerNode)

    val bridge = m ?: return null

    // 橋候補 bridge と穴・レイ交点で作る三角形の内側に頂点があれば、より角度の小さい頂点へ橋を張り替える
    // （凹んだ外周や近接する穴での自己交差を避ける）。
    val stop = bridge
    var best = bridge
    val mx = bridge.x
    val my = bridge.y
    var tanMin = Double.POSITIVE_INFINITY
    p = bridge
    do {
        val inTri =
            if (hy < my) {
                pointInTriangle(hx, hy, mx, my, qx, hy, p.x, p.y)
            } else {
                pointInTriangle(qx, hy, mx, my, hx, hy, p.x, p.y)
            }
        if (hx >= p.x && p.x >= mx && hx != p.x && inTri) {
            val tan = Math.abs(hy - p.y) / (hx - p.x)
            if (locallyInside(p, hole) && (tan < tanMin || (tan == tanMin && p.x > best.x))) {
                best = p
                tanMin = tan
            }
        }
        p = p.next
    } while (p !== stop)

    return best
}

/** a と b を橋で接続し、リングを繋ぎ替える（earcut の splitPolygon）。 */
private fun splitPolygon(
    a: Node,
    b: Node,
): Node {
    val a2 = Node(a.x, a.y, a.source)
    val b2 = Node(b.x, b.y, b.source)
    val an = a.next
    val bp = b.prev

    a.next = b
    b.prev = a
    a2.next = an
    an.prev = a2
    b2.next = a2
    a2.prev = b2
    bp.next = b2
    b2.prev = bp
    return b2
}

private fun area(
    p: Node,
    q: Node,
    r: Node,
): Double = (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y)

private fun locallyInside(
    a: Node,
    b: Node,
): Boolean =
    if (area(a.prev, a, a.next) < 0) {
        area(a, b, a.next) >= 0 && area(a, a.prev, b) >= 0
    } else {
        area(a, b, a.prev) < 0 || area(a, a.next, b) < 0
    }

private fun pointInTriangle(
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
    cx: Double,
    cy: Double,
    px: Double,
    py: Double,
): Boolean =
    (cx - px) * (ay - py) - (ax - px) * (cy - py) >= 0 &&
        (ax - px) * (by - py) - (bx - px) * (ay - py) >= 0 &&
        (bx - px) * (cy - py) - (cx - px) * (by - py) >= 0
