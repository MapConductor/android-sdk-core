package com.mapconductor.core.geocell

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.MarkerEntity
import com.mapconductor.core.marker.MarkerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HexCellRegistry] の再構築条件。
 *
 * KDTree が索引しているのはセル集合なので、同じ位置の entity を入れ直しても木は変わらない。
 * 以前はここで無条件に dirty を立てていたため、位置の変わらない更新（アイコン差し替え、
 * [com.mapconductor.core.marker.MarkerViewportSwitch] のタイル⇔ネイティブ切り替えなど。
 * `MarkerManager.updateEntity` は毎回 setPoint を呼ぶ）のあと、次の空間検索が全セルから
 * 木を作り直していた。実機（24,000 件）で 200ms 超のメインスレッド停止として観測されたもの。
 */
class HexCellRegistryRebuildTest {
    private val geocell = HexGeocell.defaultGeocell()

    private fun registry() = HexCellRegistry<Unit>(geocell = geocell, zoom = 20.0)

    private fun entity(
        id: String,
        latitude: Double,
        longitude: Double,
    ) = MarkerEntity<Unit>(
        marker = null,
        state = MarkerState(id = id, position = GeoPoint.fromLatLong(latitude, longitude)),
    )

    /** 検索を一度通して木を建て、dirty を落とした状態にする。 */
    private fun HexCellRegistry<Unit>.settle() {
        findWithinRadiusWithDistance(GeoPoint.fromLatLong(35.68, 139.76), 1.0)
        assertFalse("前提: この時点で dirty は落ちている", getStats().needsRebuild)
    }

    /** 同じ位置での再登録は木を汚さない。ここが本命。 */
    @Test
    fun `同じ位置の再登録では再構築を要求しない`() {
        val registry = registry()
        registry.setPoint(entity("a", 35.68, 139.76))
        registry.settle()

        repeat(5) { registry.setPoint(entity("a", 35.68, 139.76)) }

        assertFalse(registry.getStats().needsRebuild)
        assertEquals(1, registry.getStats().totalCells)
    }

    /** 位置が変わればセル集合が変わるので、再構築は要求される。 */
    @Test
    fun `別セルへ移ったら再構築を要求する`() {
        val registry = registry()
        val before = registry.setPoint(entity("a", 35.68, 139.76))
        registry.settle()

        val after = registry.setPoint(entity("a", 34.70, 135.50))

        assertTrue("移動後は別セルであること", before.id != after.id)
        assertTrue(registry.getStats().needsRebuild)
        assertEquals(1, registry.getStats().totalCells)
        assertEquals(setOf("a"), registry.getEntryIDsByHexCell(after)?.toSet())
    }

    /** 同一セルに残りがあるなら、1 件消してもセル集合は変わらない。 */
    @Test
    fun `同一セル内の 1 件削除では再構築を要求しない`() {
        val registry = registry()
        // zoom 20 のセルは 10cm 程度なので、同じセルに入れるには座標を完全に一致させる。
        val cell = registry.setPoint(entity("a", 35.680000, 139.760000))
        registry.setPoint(entity("b", 35.680000, 139.760000))
        assertEquals(setOf("a", "b"), registry.getEntryIDsByHexCell(cell)?.toSet())
        registry.settle()

        registry.removePoint(entity("a", 35.680000, 139.760000))

        assertFalse(registry.getStats().needsRebuild)
        assertEquals(setOf("b"), registry.getEntryIDsByHexCell(cell)?.toSet())
    }

    /** 最後の 1 件を消すとセルが消えるので、再構築は要求される。 */
    @Test
    fun `セルが空になったら再構築を要求する`() {
        val registry = registry()
        registry.setPoint(entity("a", 35.68, 139.76))
        registry.settle()

        registry.removePoint(entity("a", 35.68, 139.76))

        assertTrue(registry.getStats().needsRebuild)
        assertEquals(0, registry.getStats().totalCells)
    }

    /** 再登録を挟んでも半径検索の結果は変わらない（最適化で結果を落としていないこと）。 */
    @Test
    fun `再登録を挟んでも半径検索の結果が変わらない`() {
        val registry = registry()
        val center = GeoPoint.fromLatLong(35.68, 139.76)
        registry.setPoint(entity("a", 35.6800, 139.7600))
        registry.setPoint(entity("b", 35.6805, 139.7605))
        registry.setPoint(entity("c", 34.7000, 135.5000))

        val before = registry.findWithinRadiusWithDistance(center, 2000.0).map { it.cell.id }.toSet()
        repeat(3) {
            registry.setPoint(entity("a", 35.6800, 139.7600))
            registry.setPoint(entity("b", 35.6805, 139.7605))
        }
        val after = registry.findWithinRadiusWithDistance(center, 2000.0).map { it.cell.id }.toSet()

        assertTrue("近傍のセルが取れていること", after.isNotEmpty())
        assertEquals(before, after)
    }
}
