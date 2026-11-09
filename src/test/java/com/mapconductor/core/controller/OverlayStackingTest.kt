package com.mapconductor.core.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重なり順の決まり（ラスター < GroundImage < ポリゴン < サークル < ポリライン < マーカー）。
 * ios-sdk-core / js-sdk-core の同名テストと同じ表。
 */
class OverlayStackingTest {
    @Test
    fun `帯は下からラスター・GroundImage・ポリゴン・サークル・ポリライン・マーカー`() {
        assertEquals(
            listOf(
                OverlayKind.RasterLayer,
                OverlayKind.GroundImage,
                OverlayKind.Polygon,
                OverlayKind.Circle,
                OverlayKind.Polyline,
                OverlayKind.Marker,
            ),
            OverlayKind.entries.sortedBy { OverlayStacking.band(it) },
        )
    }

    @Test
    fun `flatZIndex はどんな zIndex でも帯をまたがない`() {
        val extremes = listOf(Int.MIN_VALUE, -35_000_000, -1, 0, 1, 1000, Int.MAX_VALUE)
        val bands = OverlayStacking.BOTTOM_TO_TOP
        for (i in 0 until bands.size - 1) {
            val lowerMax = extremes.maxOf { OverlayStacking.flatZIndex(bands[i], it) }
            val upperMin = extremes.minOf { OverlayStacking.flatZIndex(bands[i + 1], it) }
            assertTrue("${bands[i]} must stay below ${bands[i + 1]}", lowerMax < upperMin)
        }
    }

    @Test
    fun `flatZIndex は帯の中で zIndex の順を保つ`() {
        val zs = listOf(-1000, -10, -1, 0, 1, 2, 10, 1000)
        val flat = zs.map { OverlayStacking.flatZIndex(OverlayKind.Polygon, it) }
        assertEquals(flat.sorted(), flat)
        assertEquals(flat.size, flat.toSet().size)
    }

    private data class L(
        val id: String,
        val key: OverlayStacking.Key?,
    )

    private fun stack(vararg layers: L) = layers.toList()

    private fun k(
        kind: OverlayKind,
        z: Int = 0,
    ) = OverlayStacking.Key(kind, z)

    @Test
    fun `insertBefore は鍵が大きい一番下の SDK の層を選び、ベースマップの層は無視する`() {
        val layers =
            stack(
                L("basemap-fill", null),
                L("raster", k(OverlayKind.RasterLayer)),
                L("basemap-labels", null),
                L("polygon", k(OverlayKind.Polygon)),
                L("polyline", k(OverlayKind.Polyline)),
                L("markers", k(OverlayKind.Marker)),
            )
        val before = OverlayStacking.insertBefore(layers, k(OverlayKind.GroundImage)) { it.key }
        assertEquals("polygon", before?.id)
        assertEquals(
            "polyline",
            OverlayStacking.insertBefore(layers, k(OverlayKind.Circle)) { it.key }?.id,
        )
        assertNull(OverlayStacking.insertBefore(layers, k(OverlayKind.Marker, 5)) { it.key })
    }

    @Test
    fun `同じ鍵なら後から入れたものが上、zIndex が大きいものはその上`() {
        val layers =
            stack(
                L("pg-0a", k(OverlayKind.Polygon, 0)),
                L("pg-0b", k(OverlayKind.Polygon, 0)),
                L("pg-3", k(OverlayKind.Polygon, 3)),
                L("circle", k(OverlayKind.Circle)),
            )
        assertEquals("pg-3", OverlayStacking.insertBefore(layers, k(OverlayKind.Polygon, 0)) { it.key }?.id)
        assertEquals("pg-3", OverlayStacking.insertBefore(layers, k(OverlayKind.Polygon, 1)) { it.key }?.id)
        assertEquals("circle", OverlayStacking.insertBefore(layers, k(OverlayKind.Polygon, 3)) { it.key }?.id)
        assertEquals("pg-0a", OverlayStacking.insertBefore(layers, k(OverlayKind.Polygon, -1)) { it.key }?.id)
    }

    @Test
    fun `追加の順によらず同じ並びになる`() {
        val kinds = OverlayStacking.BOTTOM_TO_TOP
        listOf(kinds, kinds.reversed(), listOf(kinds[3], kinds[0], kinds[5], kinds[1], kinds[4], kinds[2])).forEach { order ->
            val layers = mutableListOf<L>()
            order.forEach { kind ->
                val key = k(kind)
                val before = OverlayStacking.insertBefore(layers, key) { it.key }
                val at = if (before == null) layers.size else layers.indexOf(before)
                layers.add(at, L(kind.name, key))
            }
            assertEquals(kinds.map { it.name }, layers.map { it.id })
        }
    }
}

/**
 * SDK が描くタイルの置き場所。プロバイダはこの鍵（GL 系）と flatZIndex（Google など）で
 * タイルのレイヤーを置く。ポリラインのタイルはポリラインの帯の一番下: ポリゴンのタイルと
 * サークルの上、ネイティブのポリラインとマーカー（のタイル）の下。
 */
class ShapeTileStackingTest {
    private val polygonTile = OverlayStacking.Key(OverlayKind.Polygon, Int.MIN_VALUE)
    private val polylineTile = OverlayStacking.Key(OverlayKind.Polyline, Int.MIN_VALUE)
    private val markerTile = OverlayStacking.Key(OverlayKind.Marker, Int.MIN_VALUE)

    @Test
    fun `ポリラインのタイルはサークルの上、ネイティブのポリラインとマーカーの下`() {
        val keys =
            listOf(
                OverlayStacking.Key(OverlayKind.Marker, Int.MIN_VALUE + 1),
                markerTile,
                OverlayStacking.Key(OverlayKind.Polyline, -1_000_000),
                polylineTile,
                OverlayStacking.Key(OverlayKind.Circle, Int.MAX_VALUE),
                OverlayStacking.Key(OverlayKind.Polygon, Int.MAX_VALUE),
                polygonTile,
            )
        assertEquals(keys.reversed(), keys.sorted())
    }

    @Test
    fun `flatZIndex でも同じ順`() {
        val polylineTileZ = OverlayStacking.flatZIndex(OverlayKind.Polyline, Int.MIN_VALUE)
        assertTrue(polylineTileZ > OverlayStacking.flatZIndex(OverlayKind.Circle, Int.MAX_VALUE))
        assertTrue(polylineTileZ > OverlayStacking.flatZIndex(OverlayKind.Polygon, Int.MIN_VALUE))
        assertTrue(polylineTileZ < OverlayStacking.flatZIndex(OverlayKind.Polyline, -1_000))
        assertTrue(polylineTileZ < OverlayStacking.flatZIndex(OverlayKind.Marker, Int.MIN_VALUE))
    }

    @Test
    fun `GL 系の層の並びでは、ポリゴンのタイルとサークルの上、ネイティブのポリラインの下へ入る`() {
        val stack = OverlayLayerStack()
        stack.put("raster-layer-mc-polygon-tiles", polygonTile)
        stack.put("circle-layer", OverlayStacking.Key(OverlayKind.Circle))
        stack.put("polyline-layer", OverlayStacking.Key(OverlayKind.Polyline))
        stack.put("raster-layer-marker-tile-1", markerTile)
        val layers =
            listOf(
                "water",
                "raster-layer-mc-polygon-tiles",
                "circle-layer",
                "polyline-layer",
                "raster-layer-marker-tile-1",
            )
        assertEquals("polyline-layer", stack.beforeId(layers, polylineTile))
    }
}

class OverlayLayerStackTest {
    @Test
    fun `台帳に無い層は無視し、スタイルを読み直しても同じ位置を返す`() {
        val stack = OverlayLayerStack()
        stack.put("raster-layer-A", OverlayStacking.Key(OverlayKind.RasterLayer))
        stack.put("polygon-fill-layer-0", OverlayStacking.Key(OverlayKind.Polygon, 0))
        stack.put("markers-layer", OverlayStacking.Key(OverlayKind.Marker))
        val layers = listOf("water", "raster-layer-A", "road-label", "polygon-fill-layer-0", "markers-layer")
        assertEquals(
            "polygon-fill-layer-0",
            stack.beforeId(layers, OverlayStacking.Key(OverlayKind.GroundImage)),
        )
        // 読み直し直後（SDK の層がまだ何も無い）なら一番上。
        assertNull(stack.beforeId(listOf("water", "road-label"), OverlayStacking.Key(OverlayKind.GroundImage)))
        stack.remove("markers-layer")
        assertNull(stack.beforeId(layers, OverlayStacking.Key(OverlayKind.Polyline)))
    }
}
