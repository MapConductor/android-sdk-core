package com.mapconductor.core.geocell

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.projection.ProjectedPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [KDTree] の構築（quickselect）が、総当たりと同じ答えを返すこと。
 *
 * 階層ごとにソートしてリストを作っていた構築を、1 本の配列の中で中央値だけを選ぶ形に
 * 変えた。木の取り違えは何も落とさず、マーカーが静かに見つからなくなるだけなので、
 * 総当たりの結果と比べる。六角セルの中心は列ごとに同じ x を持つので、同じ座標が
 * 大量に並ぶ格子でも確かめる（分割の取り違えはそこで出る）。
 */
class KDTreeBuildTest {
    private fun cellAt(
        id: String,
        x: Double,
        y: Double,
    ) = HexCell(
        coord = HexCoord(0, 0),
        centerLatLng = GeoPoint(0.0, 0.0),
        centerXY = ProjectedPoint(x, y),
        id = id,
    )

    private fun random(seed: Long): () -> Double {
        var state = seed
        return {
            state = state * 6364136223846793005L + 1442695040888963407L
            (state ushr 11).toDouble() / (1L shl 53).toDouble()
        }
    }

    private fun gridCells(): List<HexCell> =
        (0 until 40).flatMap { col ->
            (0 until 40).map { row ->
                cellAt("c${col}_$row", col * 3.0, row * 2.0 + (col % 2))
            }
        }

    private fun scatteredCells(count: Int): List<HexCell> {
        val next = random(123456789L)
        return (0 until count).map { cellAt("s$it", next() * 1000.0, next() * 1000.0) }
    }

    private fun distSq(
        a: ProjectedPoint,
        b: ProjectedPoint,
    ): Double = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)

    private fun assertMatchesBruteForce(cells: List<HexCell>) {
        val tree = KDTree(cells)
        assertEquals(cells.size, tree.getStats().nodeCount)

        val next = random(42L)
        repeat(200) {
            val query = ProjectedPoint(next() * 130.0 - 5.0, next() * 90.0 - 5.0)

            val nearest = tree.nearest(query)!!
            val best = cells.minOf { distSq(it.centerXY, query) }
            assertEquals(best, distSq(nearest.centerXY, query), 0.0)

            val radius = next() * 10.0
            val within = tree.withinRadiusWithDistance(query, radius).map { it.cell.id }.sorted()
            val expected =
                cells
                    .filter { distSq(it.centerXY, query) <= radius * radius }
                    .map { it.id }
                    .sorted()
            assertEquals(expected, within)

            val k = 1 + (next() * 8).toInt()
            val knn = tree.nearestKWithDistance(query, k).map { it.distanceMeters * it.distanceMeters }
            val expectedKnn = cells.map { distSq(it.centerXY, query) }.sorted().take(k)
            assertEquals(expectedKnn.size, knn.size)
            expectedKnn.zip(knn).forEach { (e, a) -> assertEquals(e, a, 1e-6) }
        }
    }

    @Test
    fun `同じ座標が並ぶ格子で総当たりと一致する`() = assertMatchesBruteForce(gridCells())

    @Test
    fun `ばらばらの点で総当たりと一致する`() = assertMatchesBruteForce(scatteredCells(3_000))

    @Test
    fun `渡したリストを並べ替えない`() {
        val cells = scatteredCells(500)
        val before = cells.map { it.id }
        KDTree(cells)
        assertEquals(before, cells.map { it.id })
    }
}
