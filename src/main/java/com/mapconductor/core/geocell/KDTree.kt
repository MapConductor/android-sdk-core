package com.mapconductor.core.geocell

import com.mapconductor.core.projection.ProjectedPoint
import java.util.PriorityQueue
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * K-D Tree implementation for efficient spatial queries on hex cells
 * Supports nearest neighbor, k-NN, and radius searches
 */
class KDTree(
    points: List<HexCell>,
) {
    // 呼び出し元のリストは並べ替えない
    private val root =
        if (points.isNotEmpty()) {
            val items = points.toTypedArray()
            build(items, 0, items.size, 0)
        } else {
            null
        }

    private class Node(
        val cell: HexCell,
        val left: Node?,
        val right: Node?,
        val axis: Int, // 0 for x-axis, 1 for y-axis
    )

    /**
     * Build the K-D tree recursively
     *
     * `items` の from から to の手前までの中で中央値だけを選び（quickselect）、左右を同じ配列のまま
     * 再帰する。階層ごとにソートしてリストを作ると、数万件で数百ミリ秒かかり、
     * マーカーを足し引きした直後の空間検索がその間止まる。
     */
    private fun build(
        items: Array<HexCell>,
        from: Int,
        to: Int,
        depth: Int,
    ): Node? {
        if (from >= to) return null

        val axis = depth % 2
        val mid = from + (to - from) / 2
        selectNth(items, from, to - 1, mid, axis)

        return Node(
            cell = items[mid],
            left = build(items, from, mid, depth + 1),
            right = build(items, mid + 1, to, depth + 1),
            axis = axis,
        )
    }

    /**
     * Find the nearest hex cell to a query point
     */
    fun nearest(query: ProjectedPoint): HexCell? = root?.let { nearest(it, query, null, Double.MAX_VALUE) }

    /**
     * Recursive nearest neighbor search
     */
    private fun nearest(
        node: Node,
        query: ProjectedPoint,
        best: HexCell?,
        bestDistSq: Double,
    ): HexCell? {
        val axis = node.axis
        val queryVal = if (axis == 0) query.x else query.y
        val nodeVal = if (axis == 0) node.cell.centerXY.x else node.cell.centerXY.y

        val distSq = squaredDistance(query, node.cell.centerXY).toDouble()

        var currentBest = best
        var currentBestDistSq = bestDistSq

        // Update best if current node is closer
        if (distSq < currentBestDistSq) {
            currentBest = node.cell
            currentBestDistSq = distSq
        }

        // Determine which subtree to search first
        val (nearChild, farChild) =
            if (queryVal < nodeVal) {
                node.left to node.right
            } else {
                node.right to node.left
            }

        // Search near subtree
        nearChild?.let { near ->
            currentBest = nearest(near, query, currentBest, currentBestDistSq)
            currentBestDistSq = currentBest?.let {
                squaredDistance(query, it.centerXY).toDouble()
            } ?: currentBestDistSq
        }

        // Search far subtree if it might contain closer points
        farChild?.let { far ->
            val axisDist = (queryVal - nodeVal).pow(2).toDouble()
            if (axisDist < currentBestDistSq) {
                currentBest = nearest(far, query, currentBest, currentBestDistSq)
            }
        }

        return currentBest
    }

    /**
     * Find the nearest hex cell with distance
     */
    fun nearestWithDistance(query: ProjectedPoint): HexCellWithDistance? {
        val cell = nearest(query) ?: return null
        val distance = distance(query, cell.centerXY).toDouble()
        return HexCellWithDistance(cell, distance)
    }

    /**
     * Find k nearest hex cells with distances
     */
    fun nearestKWithDistance(
        query: ProjectedPoint,
        k: Int,
    ): List<HexCellWithDistance> {
        require(k > 0) { "k must be positive" }

        if (root == null) return emptyList()

        val queue =
            PriorityQueue<Pair<Double, HexCell>>(
                compareByDescending { it.first },
            )

        nearestK(root, query, k, queue)

        return queue
            .map { (distSq, cell) ->
                HexCellWithDistance(cell, sqrt(distSq))
            }.sortedBy { it.distanceMeters }
    }

    /**
     * Recursive k-nearest neighbor search
     */
    private fun nearestK(
        node: Node,
        query: ProjectedPoint,
        k: Int,
        queue: PriorityQueue<Pair<Double, HexCell>>,
    ) {
        val distSq = squaredDistance(query, node.cell.centerXY).toDouble()

        if (queue.size < k) {
            queue.offer(distSq to node.cell)
        } else if (distSq < queue.peek()!!.first) {
            queue.poll()
            queue.offer(distSq to node.cell)
        }

        val axis = node.axis
        val queryVal = if (axis == 0) query.x else query.y
        val nodeVal = if (axis == 0) node.cell.centerXY.x else node.cell.centerXY.y

        val (nearChild, farChild) =
            if (queryVal < nodeVal) {
                node.left to node.right
            } else {
                node.right to node.left
            }

        // Search near subtree
        nearChild?.let { nearestK(it, query, k, queue) }

        // Search far subtree if it might contain closer points
        farChild?.let {
            val axisDist = (queryVal - nodeVal).pow(2).toDouble()
            if (queue.size < k || axisDist < queue.peek()!!.first) {
                nearestK(it, query, k, queue)
            }
        }
    }

    /**
     * Find all hex cells within a radius with distances
     */
    fun withinRadiusWithDistance(
        query: ProjectedPoint,
        radius: Double,
    ): List<HexCellWithDistance> {
        require(radius >= 0) { "Radius must be non-negative" }

        if (root == null) return emptyList()

        val radiusSq = radius * radius
        val result = mutableListOf<HexCellWithDistance>()
        withinRadius(root, query, radiusSq, result)
        return result.sortedBy { it.distanceMeters }
    }

    /**
     * Cells within [radius], unordered.
     *
     * The sorted, distance-carrying variant exists for callers that want the
     * nearest first. Bounds queries do not: they use every cell and throw the
     * distances away, and sorting them is the dominant cost of the search. A
     * cell carries a String, so sorting 20k of them moves 20k references
     * through some 286k comparisons — measured at 42 ms of a 64 ms query on an
     * iPad Pro, against 1.8 ms without the sort.
     */
    fun withinRadius(
        query: ProjectedPoint,
        radius: Double,
    ): List<HexCell> {
        require(radius >= 0) { "Radius must be non-negative" }
        val start = root ?: return emptyList()

        val result = mutableListOf<HexCell>()
        withinRadiusUnordered(start, query, radius * radius, result)
        return result
    }

    private fun withinRadiusUnordered(
        node: Node,
        query: ProjectedPoint,
        radiusSq: Double,
        result: MutableList<HexCell>,
    ) {
        if (squaredDistance(query, node.cell.centerXY) <= radiusSq) {
            result.add(node.cell)
        }

        val queryVal = if (node.axis == 0) query.x.toDouble() else query.y.toDouble()
        val nodeVal =
            if (node.axis == 0) {
                node.cell.centerXY.x
                    .toDouble()
            } else {
                node.cell.centerXY.y
                    .toDouble()
            }
        val nearChild = if (queryVal < nodeVal) node.left else node.right
        val farChild = if (queryVal < nodeVal) node.right else node.left

        if (nearChild != null) withinRadiusUnordered(nearChild, query, radiusSq, result)
        val axisDistance = queryVal - nodeVal
        if (axisDistance * axisDistance <= radiusSq && farChild != null) {
            withinRadiusUnordered(farChild, query, radiusSq, result)
        }
    }

    /**
     * Recursive radius search
     */
    private fun withinRadius(
        node: Node,
        query: ProjectedPoint,
        radiusSq: Double,
        result: MutableList<HexCellWithDistance>,
    ) {
        val distSq = squaredDistance(query, node.cell.centerXY).toDouble()

        if (distSq <= radiusSq) {
            result.add(HexCellWithDistance(node.cell, sqrt(distSq)))
        }

        val axis = node.axis
        val queryVal = if (axis == 0) query.x else query.y
        val nodeVal = if (axis == 0) node.cell.centerXY.x else node.cell.centerXY.y

        val (nearChild, farChild) =
            if (queryVal < nodeVal) {
                node.left to node.right
            } else {
                node.right to node.left
            }

        // Search near subtree
        nearChild?.let { withinRadius(it, query, radiusSq, result) }

        // Search far subtree if it intersects with query circle
        farChild?.let {
            val axisDist = (queryVal - nodeVal).pow(2).toDouble()
            if (axisDist <= radiusSq) {
                withinRadius(it, query, radiusSq, result)
            }
        }
    }

    /**
     * Calculate squared Euclidean distance between two points
     */
    private fun squaredDistance(
        a: ProjectedPoint,
        b: ProjectedPoint,
    ): Double {
        val deltaX = a.x - b.x
        val deltaY = a.y - b.y
        return deltaX * deltaX + deltaY * deltaY
    }

    /**
     * Calculate Euclidean distance between two points
     */
    private fun distance(
        a: ProjectedPoint,
        b: ProjectedPoint,
    ): Double = sqrt(squaredDistance(a, b))

    /**
     * Get statistics about the tree structure
     */
    fun getStats(): KDTreeStats =
        KDTreeStats(
            nodeCount = countNodes(root),
            maxDepth = maxDepth(root),
            isEmpty = root == null,
        )

    private fun countNodes(node: Node?): Int =
        if (node == null) 0 else 1 + countNodes(node.left) + countNodes(node.right)

    private fun maxDepth(node: Node?): Int =
        if (node == null) 0 else 1 + maxOf(maxDepth(node.left), maxDepth(node.right))
}

/**
 * Statistics about the K-D tree structure
 */
data class KDTreeStats(
    val nodeCount: Int,
    val maxDepth: Int,
    val isEmpty: Boolean,
)

private fun axisValue(
    cell: HexCell,
    axis: Int,
): Double = if (axis == 0) cell.centerXY.x else cell.centerXY.y

/**
 * `items` の left から right まで（両端を含む）を、k 番目の要素が軸の値で k 番目になるよう並べ替える
 * （左は以下、右は以上）。Wirth の quickselect。
 *
 * 六角セルの中心は列ごとに同じ x を持つので、等しい値が大量に並ぶ。
 * 左右から寄せる分割にしてあるのはそのため（片側に寄せる分割だと O(n²) になる）。
 */
private fun selectNth(
    items: Array<HexCell>,
    left: Int,
    right: Int,
    k: Int,
    axis: Int,
) {
    var lo = left
    var hi = right
    while (lo < hi) {
        val pivot = axisValue(items[k], axis)
        var i = lo
        var j = hi
        do {
            while (axisValue(items[i], axis) < pivot) i++
            while (pivot < axisValue(items[j], axis)) j--
            if (i <= j) {
                val tmp = items[i]
                items[i] = items[j]
                items[j] = tmp
                i++
                j--
            }
        } while (i <= j)
        if (j < k) lo = i
        if (k < i) hi = j
    }
}
