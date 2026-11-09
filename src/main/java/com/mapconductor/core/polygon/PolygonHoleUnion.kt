package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * 重なり合う穴リング同士を平面（経度緯度）座標系で結合する。外部ジオメトリライブラリ非依存。
 *
 * react-sdk の `PolygonUnion.ts` からの移植（ios-sdk にも同等の自前実装がある）。
 * ブーリアン演算のスイープラインではなく、全ての穴の辺から平面配置（planar arrangement）を作り
 * （交点ごとに辺を分割）、結合図形の外側境界に載る部分辺だけを残し（辺の左右どちらか一方のみが
 * 内部になる辺。点包含カバレッジで判定）、それらを繋ぎ直してリングへ戻す。
 *
 * 注意:
 * - 平面幾何であり測地線ではない。非常に大きなポリゴンや極付近では球面上の期待と異なり得る。
 * - 何らかの失敗時は入力のリングをそのまま返す（呼び出し元は同一インスタンスかどうかで変更有無を判定できる）。
 */
fun unionHoleRings(holes: List<List<GeoPointInterface>>): List<List<GeoPointInterface>> {
    if (holes.size <= 1) return holes

    return runCatching {
        // 原点相対で計算することで座標をフィーチャースケールに保ち、
        // 経度緯度 0 から離れた場所（例: 東京）でも外積の精度を保つ。
        val origin = firstFinite(holes) ?: return holes

        val rings = holes.mapNotNull { toRing(it, origin) }
        if (rings.size <= 1) return holes
        // Holes that neither cross, touch nor contain one another are already
        // their own union. Checking that is linear; the union is quadratic in
        // the hole edges, which a street-network parcel with 80 holes and
        // 8,000 hole vertices turns into seconds.
        // The union's other promise still holds: holes come back clockwise.
        // Only those wound the other way are copied, so holes that already
        // were come back as the same list.
        if (rings.size == holes.size && holesAreDisjoint(rings)) {
            if (rings.none { signedArea(it) > 0.0 }) return holes
            return holes.mapIndexed { i, hole -> if (signedArea(rings[i]) > 0.0) hole.asReversed() else hole }
        }

        val merged = unionRings(rings)
        if (merged.isEmpty()) return holes

        merged.map { ring ->
            val geo =
                ring.map { p ->
                    GeoPoint.fromLatLong(latitude = p.y + origin.y, longitude = p.x + origin.x)
                }
            // 穴の巻き方向を時計回りへ正規化する（レンダラーは外周と逆巻きを期待する）。
            // unionRings は反時計回りのリングを出力する。
            if (signedArea(ring) > 0.0) geo.asReversed() else geo
        }
    }.getOrElse { holes }
}

// ─── 平面 union の実装 ──────────────────────────────────────────────────────

private data class Vec(
    val x: Double,
    val y: Double,
)

private data class Edge(
    val a: Vec,
    val b: Vec,
)

/** スナップ後の座標を整数格子で表すキー（リング連結時は端点の完全一致が必要）。 */
private data class VecKey(
    val ix: Long,
    val iy: Long,
)

// スナップ格子（度）: 連結には端点の完全一致が必要。約 0.1mm。
private const val Q = 1e-9

// フィーチャースケール座標での平行・共線・線分上判定の許容誤差。
private const val EPS = 1e-12

// 中点からこれより近い部分辺はその中点を通っている（度。Q よりはるかに小さい）。
private const val ON_EDGE = 1e-12

private fun unionRings(rings: List<List<Vec>>): List<List<Vec>> {
    // 全リングの有向辺（閉じたものとして扱う）。
    val edges = mutableListOf<Edge>()
    for (ring in rings) {
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            if (!samePoint(a, b)) edges.add(Edge(a, b))
        }
    }

    val subEdges = splitEdges(edges)
    val boundary = classifyBoundary(subEdges, rings)
    return traceRings(boundary)
}

// intersectionPoints が線分の外接矩形の外まで拾う距離（度）。共線判定は直線から EPS / |seg| までの
// 辺を受け入れ、sqrt(EPS) = 1e-6 より短い線分はそこで除かれるので、高々 2e-6。
private const val MEET_PAD = 4e-6

// 部分辺の中点から左右の標本点までの距離の上限の 2 倍（度）。
private const val CLEARANCE_CAP = 2e-5

/**
 * 辺を外接矩形で一様格子に登録し、探索をすべての辺ではなく矩形の近くの辺だけに絞る。
 * 辺の矩形が触れるすべてのセルに登録するので、矩形が触れるセルを全部見れば、
 * その矩形と出会い得る辺はすべて見つかる。
 */
private class EdgeGrid(
    edges: List<Edge>,
    pad: Double,
) {
    private val side: Int
    private val minX: Double
    private val minY: Double
    private val cellW: Double
    private val cellH: Double
    private val cells = HashMap<Int, IntArrayList>()
    private val seen = IntArray(edges.size)
    private var stamp = 0

    init {
        var x0 = Double.POSITIVE_INFINITY
        var y0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY
        var y1 = Double.NEGATIVE_INFINITY
        for (e in edges) {
            x0 = minOf(x0, e.a.x, e.b.x)
            x1 = maxOf(x1, e.a.x, e.b.x)
            y0 = minOf(y0, e.a.y, e.b.y)
            y1 = maxOf(y1, e.a.y, e.b.y)
        }
        side = ceil(sqrt(edges.size.toDouble())).toInt().coerceIn(1, 1024)
        minX = x0
        minY = y0
        cellW = ((x1 - x0) / side).let { if (it > 0.0) it else 1.0 }
        cellH = ((y1 - y0) / side).let { if (it > 0.0) it else 1.0 }
        for (i in edges.indices) {
            val e = edges[i]
            forCells(
                minOf(e.a.x, e.b.x) - pad,
                minOf(e.a.y, e.b.y) - pad,
                maxOf(e.a.x, e.b.x) + pad,
                maxOf(e.a.y, e.b.y) + pad,
            ) { key -> cells.getOrPut(key) { IntArrayList() }.add(i) }
        }
    }

    /** 矩形が触れるセルに登録された辺の番号。各辺 1 回、順序は不定。 */
    fun near(
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
        out: IntArrayList,
    ): IntArrayList {
        out.clear()
        val current = ++stamp
        forCells(minX, minY, maxX, maxY) { key ->
            val bucket = cells[key] ?: return@forCells
            for (k in 0 until bucket.size) {
                val i = bucket[k]
                if (seen[i] == current) continue
                seen[i] = current
                out.add(i)
            }
        }
        return out
    }

    private inline fun forCells(
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
        visit: (Int) -> Unit,
    ) {
        val cx0 = cell(minX, this.minX, cellW)
        val cx1 = cell(maxX, this.minX, cellW)
        val cy0 = cell(minY, this.minY, cellH)
        val cy1 = cell(maxY, this.minY, cellH)
        for (cx in cx0..cx1) {
            for (cy in cy0..cy1) visit(cx * side + cy)
        }
    }

    private fun cell(
        v: Double,
        min: Double,
        size: Double,
    ): Int = floor((v - min) / size).coerceIn(0.0, (side - 1).toDouble()).toInt()
}

/** 箱詰めしない Int の可変長配列。 */
private class IntArrayList {
    private var items = IntArray(4)
    var size = 0
        private set

    operator fun get(index: Int): Int = items[index]

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    fun clear() {
        size = 0
    }
}

/** 各辺を、他の辺が交差・接触する全ての点で分割する。 */
private fun splitEdges(edges: List<Edge>): List<Edge> {
    val result = mutableListOf<Edge>()
    // この辺の矩形から MEET_PAD 以内に矩形が来る辺だけが、この辺と出会い得る。
    val grid = EdgeGrid(edges, MEET_PAD)
    val near = IntArrayList()
    for (i in edges.indices) {
        val seg = edges[i]
        val dir = sub(seg.b, seg.a)
        val lenSq = dot(dir, dir)
        if (lenSq < EPS) continue

        // 分割点を線分上のパラメータ t ∈ [0, 1] として集める。
        val params = LinkedHashMap<VecKey, Double>()

        fun add(p: Vec) {
            val t = clamp01(dot(sub(p, seg.a), dir) / lenSq)
            params[keyOf(snap(add2(seg.a, scale(dir, t))))] = t
        }
        add(seg.a)
        add(seg.b)
        grid.near(
            minOf(seg.a.x, seg.b.x) - MEET_PAD,
            minOf(seg.a.y, seg.b.y) - MEET_PAD,
            maxOf(seg.a.x, seg.b.x) + MEET_PAD,
            maxOf(seg.a.y, seg.b.y) + MEET_PAD,
            near,
        )
        for (k in 0 until near.size) {
            val j = near[k]
            if (i == j) continue
            for (p in intersectionPoints(seg, edges[j])) add(p)
        }

        val points =
            params.entries
                .sortedBy { it.value }
                .map { snap(add2(seg.a, scale(dir, it.value))) }
        for (k in 0 until points.size - 1) {
            if (!samePoint(points[k], points[k + 1])) {
                result.add(Edge(points[k], points[k + 1]))
            }
        }
    }
    return result
}

/**
 * 結合図形の境界に載る部分辺のみを残す。結合図形の内部が左側に来る向きに揃え
 * （反時計回りのリングになる）、重複は除去する。
 */
private fun classifyBoundary(
    subEdges: List<Edge>,
    rings: List<List<Vec>>,
): List<Edge> {
    val out = mutableListOf<Edge>()
    val seen = HashSet<Pair<VecKey, VecKey>>()
    val grid = EdgeGrid(subEdges, 0.0)
    val near = IntArrayList()
    val ringsBounds = rings.map(::ringBounds)
    for (seg in subEdges) {
        val dir = sub(seg.b, seg.a)
        val len = hypot(dir.x, dir.y)
        if (len < EPS) continue

        val mid = add2(seg.a, scale(dir, 0.5))
        // 単位左法線。中点の左右すぐ横でカバレッジを標本化する。分割後は他の辺が
        // この部分辺の内部を横切らないため、最寄りの他の辺までの距離より短いオフセットなら
        // この辺が隔てる 2 セル内に収まる。固定オフセットでは収まらない: 別の穴がそれより
        // 近くを通る（1e-5 度離れた 2 つの穴）と標本点がその穴に入り、辺が両側とも内部に
        // 見えて落とされ、繋ぎ直したリングが穴を横切る。
        val nx = -dir.y / len
        val ny = dir.x / len
        val off = min(len * 0.25, 0.5 * clearance(mid, CLEARANCE_CAP, subEdges, grid, near))
        val leftInside = coverage(Vec(mid.x + nx * off, mid.y + ny * off), rings, ringsBounds) > 0
        val rightInside = coverage(Vec(mid.x - nx * off, mid.y - ny * off), rings, ringsBounds) > 0
        if (leftInside == rightInside) continue // 両側とも内部、または両側とも外部

        // 内部が左側に来る向きへ揃える。
        val a = if (leftInside) seg.a else seg.b
        val b = if (leftInside) seg.b else seg.a
        val key = keyOf(a) to keyOf(b)
        if (!seen.add(key)) continue
        out.add(Edge(a, b))
    }
    return out
}

/**
 * `point`（部分辺の中点）から、それを通らない最寄りの部分辺までの距離。`cap` より近いものが
 * 無ければ `cap`。その部分辺自身と、他のリングから来た同じ部分辺は中点を通るので除く。
 * 点のまわり `cap` の矩形の近くに登録された部分辺だけを調べる。`cap` より近いものはその中にある。
 */
private fun clearance(
    point: Vec,
    cap: Double,
    subEdges: List<Edge>,
    grid: EdgeGrid,
    near: IntArrayList,
): Double {
    var best = cap
    grid.near(point.x - cap, point.y - cap, point.x + cap, point.y + cap, near)
    for (k in 0 until near.size) {
        val e = subEdges[near[k]]
        if (minOf(e.a.x, e.b.x) > point.x + best ||
            maxOf(e.a.x, e.b.x) < point.x - best ||
            minOf(e.a.y, e.b.y) > point.y + best ||
            maxOf(e.a.y, e.b.y) < point.y - best
        ) {
            continue
        }
        val d = distanceToSegment(point, e)
        if (d > ON_EDGE && d < best) best = d
    }
    return best
}

private fun distanceToSegment(
    p: Vec,
    e: Edge,
): Double {
    val d = sub(e.b, e.a)
    val lenSq = dot(d, d)
    val t = if (lenSq > 0.0) clamp01(dot(sub(p, e.a), d) / lenSq) else 0.0
    val q = add2(e.a, scale(d, t))
    return hypot(p.x - q.x, p.y - q.y)
}

/** 有向境界辺を連結して閉リングにする。 */
private fun traceRings(edges: List<Edge>): List<List<Vec>> {
    val byStart = HashMap<VecKey, MutableList<Int>>()
    edges.forEachIndexed { index, edge ->
        byStart.getOrPut(keyOf(edge.a)) { mutableListOf() }.add(index)
    }

    val used = BooleanArray(edges.size)
    val rings = mutableListOf<List<Vec>>()

    for (i in edges.indices) {
        if (used[i]) continue
        val ring = mutableListOf<Vec>()
        var current = i
        while (current != -1 && !used[current] && ring.size <= edges.size) {
            used[current] = true
            ring.add(edges[current].a)
            current = nextEdge(edges, byStart, used, edges[current])
        }
        if (ring.size >= 3) rings.add(ring)
    }
    return rings
}

/**
 * `edge.b` から続く未使用の次の辺。共有頂点（続きが複数）では、入ってきた方向の逆向きから
 * 最も鋭い時計回りの曲がりを選ぶ — 内部を左に保ち、他の境界曲線と交差しない
 * 標準的な「頂点まわりの次の辺」の選択。
 */
private fun nextEdge(
    edges: List<Edge>,
    byStart: Map<VecKey, List<Int>>,
    used: BooleanArray,
    edge: Edge,
): Int {
    val candidates = (byStart[keyOf(edge.b)] ?: emptyList()).filter { !used[it] }
    if (candidates.isEmpty()) return -1
    if (candidates.size == 1) return candidates[0]

    val back = atan2(edge.a.y - edge.b.y, edge.a.x - edge.b.x)
    var best = -1
    var bestAngle = Double.POSITIVE_INFINITY
    for (index in candidates) {
        val out = edges[index]
        var angle = back - atan2(out.b.y - out.a.y, out.b.x - out.a.x)
        angle = ((angle % (2 * PI)) + 2 * PI) % (2 * PI)
        if (angle < 1e-9) angle += 2 * PI // 来た道への U ターンは最後の候補にする
        if (angle < bestAngle) {
            bestAngle = angle
            best = index
        }
    }
    return best
}

/**
 * True when no two rings cross, touch or nest. Edges are binned into a uniform
 * grid and only pairs sharing a cell are tested, so the cost grows with the
 * edge count rather than its square. Same as js-sdk-core's `holesAreDisjoint`.
 */
private fun holesAreDisjoint(rings: List<List<Vec>>): Boolean {
    val boxes = rings.map(::ringBox)
    // A ring inside another: one of its vertices is inside it.
    for (i in rings.indices) {
        val p = rings[i][0]
        for (j in rings.indices) {
            if (i == j) continue
            val b = boxes[j]
            if (p.x >= b[0] && p.x <= b[2] && p.y >= b[1] && p.y <= b[3] && pointInRing(p, rings[j])) {
                return false
            }
        }
    }

    val edges = ArrayList<Edge>()
    val edgeRing = ArrayList<Int>()
    for ((r, ring) in rings.withIndex()) {
        for (i in ring.indices) {
            edges.add(Edge(ring[i], ring[(i + 1) % ring.size]))
            edgeRing.add(r)
        }
    }
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    for (b in boxes) {
        minX = minOf(minX, b[0])
        minY = minOf(minY, b[1])
        maxX = maxOf(maxX, b[2])
        maxY = maxOf(maxY, b[3])
    }
    val side =
        kotlin.math
            .ceil(kotlin.math.sqrt(edges.size.toDouble()))
            .toInt()
            .coerceIn(1, 1024)
    // Padded by EPS so an edge lying on a cell border is in both cells.
    val cellW = ((maxX - minX) / side).takeIf { it != 0.0 } ?: 1.0
    val cellH = ((maxY - minY) / side).takeIf { it != 0.0 } ?: 1.0

    fun cellOf(
        v: Double,
        min: Double,
        size: Double,
    ): Int =
        kotlin.math
            .floor((v - min) / size)
            .toInt()
            .coerceIn(0, side - 1)
    val cells = HashMap<Int, MutableList<Int>>()
    for ((e, edge) in edges.withIndex()) {
        val a = edge.a
        val b = edge.b
        val x0 = cellOf(minOf(a.x, b.x) - EPS, minX, cellW)
        val x1 = cellOf(maxOf(a.x, b.x) + EPS, minX, cellW)
        val y0 = cellOf(minOf(a.y, b.y) - EPS, minY, cellH)
        val y1 = cellOf(maxOf(a.y, b.y) + EPS, minY, cellH)
        for (cx in x0..x1) {
            for (cy in y0..y1) {
                cells.getOrPut(cx * side + cy) { ArrayList(4) }.add(e)
            }
        }
    }
    for (bucket in cells.values) {
        for (i in bucket.indices) {
            for (j in i + 1 until bucket.size) {
                val p = bucket[i]
                val q = bucket[j]
                if (edgeRing[p] != edgeRing[q] && intersectionPoints(edges[p], edges[q]).isNotEmpty()) return false
            }
        }
    }
    return true
}

/** minX, minY, maxX, maxY. */
private fun ringBox(ring: List<Vec>): DoubleArray {
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    for (p in ring) {
        minX = minOf(minX, p.x)
        maxX = maxOf(maxX, p.x)
        minY = minOf(minY, p.y)
        maxY = maxOf(maxY, p.y)
    }
    return doubleArrayOf(minX, minY, maxX, maxY)
}

/** 線分 `other` が線分 `seg` と出会う点（交差・T 字接触・共線重複の端点）。 */
private fun intersectionPoints(
    seg: Edge,
    other: Edge,
): List<Vec> {
    val r = sub(seg.b, seg.a)
    val s = sub(other.b, other.a)
    val qp = sub(other.a, seg.a)
    val rxs = cross(r, s)
    val qpxr = cross(qp, r)

    if (abs(rxs) < EPS && abs(qpxr) < EPS) {
        // 共線: `seg` 上に落ちる重複区間の端点を返す。
        val rr = dot(r, r)
        if (rr < EPS) return emptyList()
        var t0 = dot(sub(other.a, seg.a), r) / rr
        var t1 = dot(sub(other.b, seg.a), r) / rr
        if (t0 > t1) {
            val tmp = t0
            t0 = t1
            t1 = tmp
        }
        val lo = maxOf(0.0, t0)
        val hi = minOf(1.0, t1)
        if (lo > hi + EPS) return emptyList()
        val points = mutableListOf(add2(seg.a, scale(r, lo)))
        if (hi > lo + EPS) points.add(add2(seg.a, scale(r, hi)))
        return points
    }
    if (abs(rxs) < EPS) return emptyList() // 平行で非交差

    val t = cross(qp, s) / rxs
    val u = cross(qp, r) / rxs
    if (t < -EPS || t > 1 + EPS || u < -EPS || u > 1 + EPS) return emptyList()
    return listOf(add2(seg.a, scale(r, clamp01(t))))
}

/**
 * 点を内部に含むリングの数。緯度の範囲が点の緯度をまたがないリングには pointInRing が数える辺が
 * 無いので飛ばす。
 */
private fun coverage(
    point: Vec,
    rings: List<List<Vec>>,
    bounds: List<DoubleArray>,
): Int {
    var count = 0
    for (i in rings.indices) {
        val b = bounds[i]
        if (point.y < b[1] || point.y >= b[3]) continue
        if (pointInRing(point, rings[i])) count++
    }
    return count
}

/** 偶奇規則のレイキャスティングによる点包含判定（巻き方向に依存しない）。 */
private fun pointInRing(
    point: Vec,
    ring: List<Vec>,
): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[j]
        if ((a.y > point.y) != (b.y > point.y)) {
            val x = a.x + ((point.y - a.y) / (b.y - a.y)) * (b.x - a.x)
            if (point.x < x) inside = !inside
        }
        j = i
    }
    return inside
}

// ─── リング／ベクトルのヘルパー ─────────────────────────────────────────────

private fun firstFinite(holes: List<List<GeoPointInterface>>): Vec? {
    for (hole in holes) {
        for (p in hole) {
            if (p.latitude.isFinite() && p.longitude.isFinite()) {
                return Vec(x = p.longitude, y = p.latitude)
            }
        }
    }
    return null
}

/** 開いた・重複頂点除去済み・原点相対のリング。縮退している場合は null。 */
private fun toRing(
    hole: List<GeoPointInterface>,
    origin: Vec,
): List<Vec>? {
    val points = mutableListOf<Vec>()
    for (p in hole) {
        if (!p.latitude.isFinite() || !p.longitude.isFinite()) continue
        val point = snap(Vec(x = p.longitude - origin.x, y = p.latitude - origin.y))
        if (points.isEmpty() || !samePoint(points.last(), point)) {
            points.add(point)
        }
    }
    while (points.size >= 2 && samePoint(points.first(), points.last())) {
        points.removeAt(points.size - 1)
    }
    return if (points.size >= 3) points else null
}

/** リングの外接矩形 [minX, minY, maxX, maxY]。 */
private fun ringBounds(ring: List<Vec>): DoubleArray {
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    for (p in ring) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }
    return doubleArrayOf(minX, minY, maxX, maxY)
}

private fun signedArea(ring: List<Vec>): Double {
    var area = 0.0
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[(i + 1) % ring.size]
        area += a.x * b.y - b.x * a.y
    }
    return area / 2.0
}

private fun sub(
    a: Vec,
    b: Vec,
) = Vec(a.x - b.x, a.y - b.y)

private fun add2(
    a: Vec,
    b: Vec,
) = Vec(a.x + b.x, a.y + b.y)

private fun scale(
    a: Vec,
    k: Double,
) = Vec(a.x * k, a.y * k)

private fun dot(
    a: Vec,
    b: Vec,
) = a.x * b.x + a.y * b.y

private fun cross(
    a: Vec,
    b: Vec,
) = a.x * b.y - a.y * b.x

private fun clamp01(t: Double) =
    if (t < 0.0) {
        0.0
    } else if (t > 1.0) {
        1.0
    } else {
        t
    }

private fun snap(p: Vec) = Vec((p.x / Q).roundToLong() * Q, (p.y / Q).roundToLong() * Q)

private fun keyOf(p: Vec) = VecKey((p.x / Q).roundToLong(), (p.y / Q).roundToLong())

private fun samePoint(
    a: Vec,
    b: Vec,
) = keyOf(a) == keyOf(b)
