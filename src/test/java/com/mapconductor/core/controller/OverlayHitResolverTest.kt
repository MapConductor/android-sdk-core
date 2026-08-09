package com.mapconductor.core.controller

import com.mapconductor.core.circle.CircleController
import com.mapconductor.core.circle.CircleEntity
import com.mapconductor.core.circle.CircleEntityInterface
import com.mapconductor.core.circle.CircleManager
import com.mapconductor.core.circle.CircleOverlayRendererInterface
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageController
import com.mapconductor.core.groundimage.GroundImageEntity
import com.mapconductor.core.groundimage.GroundImageEntityInterface
import com.mapconductor.core.groundimage.GroundImageManager
import com.mapconductor.core.groundimage.GroundImageOverlayRendererInterface
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.polygon.PolygonController
import com.mapconductor.core.polygon.PolygonEntity
import com.mapconductor.core.polygon.PolygonEntityInterface
import com.mapconductor.core.polygon.PolygonManager
import com.mapconductor.core.polygon.PolygonOverlayRendererInterface
import com.mapconductor.core.polygon.PolygonState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * クリックカスケードの順序規則のテスト。
 *
 * ## 何を守っているか
 *
 * 1. **探索順が `circle → groundImage → polyline → polygon`**（マーカーはこの手前、
 *    別経路）。8 プロバイダに散らばっていた同じカスケードをコアへ畳んだので、
 *    ここが唯一の正。
 * 2. **先勝ち。必ず 1 つだけ配送する。** 重なっているとき両方に飛んだり、
 *    オーバーレイに当たったのに地図クリックまで飛んだりしてはいけない。
 * 3. **`clickable = false` は透過**。当たらなかったことにして次の層へ進める
 *    （握り潰すと下のオーバーレイにも地図クリックにも流れない）。
 * 4. **解決時点では副作用が無い**。配送は [OverlayHit.dispatch] を呼ぶまで起きない。
 *
 * ## ポリラインがここに無い理由
 *
 * `PolylineManager.find` は `ResourceProvider.dpToPx`（＝ `Resources.getSystem()`）を
 * 通るため素の JVM テストでは動かない。ポリラインの
 * 「配送座標は線上の最近傍点」という規則は実機の `polyline-click` で確認する。
 * 順序の中でポリラインが占める位置は [CANONICAL_ORDER の内容][順序] で押さえてある。
 */
class OverlayHitResolverTest {
    private val inside = GeoPoint.fromLatLong(0.0, 0.0)
    private val outside = GeoPoint.fromLatLong(80.0, 170.0)

    // ── 順序規則そのもの（種別だけを持つフェイクで検証） ──────────────────

    @Test
    fun `正準順は circle groundImage polyline polygon`() {
        assertEquals(
            listOf(
                OverlayKind.Circle,
                OverlayKind.GroundImage,
                OverlayKind.Polyline,
                OverlayKind.Polygon,
            ),
            OverlayHitResolver.CANONICAL_ORDER,
        )
        // マーカーは含めない。判定手段（画面投影）が違うので別の口
        // （BaseMapViewController.dispatchMarkerTap）に分けてある。
        assertTrue(OverlayKind.Marker !in OverlayHitResolver.CANONICAL_ORDER)
    }

    @Test
    fun `登録順ではなく正準順で探索する`() {
        // わざと逆順に登録する。polygon が先に登録されていても circle が先に当たる。
        val polygon = FakeSlotted(OverlayKind.Polygon)
        val circle = FakeSlotted(OverlayKind.Circle)
        val hit =
            OverlayHitResolver.firstHit(listOf(polygon, circle)) { it }
        assertSame(circle, hit)
    }

    @Test
    fun `先に当たった 1 つで止める`() {
        val circle = FakeSlotted(OverlayKind.Circle)
        val groundImage = FakeSlotted(OverlayKind.GroundImage)
        val probed = mutableListOf<OverlayKind>()

        OverlayHitResolver.firstHit(listOf(circle, groundImage)) {
            probed += it.kind
            it
        }

        // circle で止まる。groundImage は問い合わせすらされない。
        assertEquals(listOf(OverlayKind.Circle), probed)
    }

    @Test
    fun `同じ種別が複数あれば登録順に試す`() {
        // マーカークラスタリングは Marker 種別で追加のコントローラを登録する。
        val first = FakeSlotted(OverlayKind.Polygon)
        val second = FakeSlotted(OverlayKind.Polygon)
        var skipFirst = true
        val hit =
            OverlayHitResolver.firstHit(listOf(first, second)) {
                if (it === first && skipFirst) {
                    skipFirst = false
                    null
                } else {
                    it
                }
            }
        assertSame(second, hit)
    }

    @Test
    fun `スロットに参加しないコントローラは対象外`() {
        // カメラ購読のためだけに登録する拡張モジュール（android-heatmap 等）を
        // カスケードに巻き込まない。
        val plain = FakePlain()
        assertNull(OverlayHitResolver.firstHit(listOf(plain)) { it })
    }

    @Test
    fun `order に無い種別は見ない`() {
        val raster = FakeSlotted(OverlayKind.RasterLayer)
        assertNull(OverlayHitResolver.firstHit(listOf(raster)) { it })
    }

    // ── 実物のコントローラで解決と配送 ──────────────────────────────────

    @Test
    fun `円の中のタップは Circle として解決される`() {
        val controller = circleController(clickable = true)
        val hit = OverlayHitResolver.resolve(listOf(controller), inside)

        assertEquals(OverlayKind.Circle, hit?.kind)
        assertEquals(inside.latitude, hit?.clicked?.latitude ?: Double.NaN, 0.0)
    }

    @Test
    fun `円の外のタップは解決されない`() {
        val controller = circleController(clickable = true)
        assertNull(OverlayHitResolver.resolve(listOf(controller), outside))
    }

    @Test
    fun `解決しただけでは配送しない`() {
        var delivered = 0
        val controller = circleController(clickable = true) { delivered++ }

        val hit = OverlayHitResolver.resolve(listOf(controller), inside)
        assertEquals("resolve は副作用を持たない", 0, delivered)

        hit?.dispatch()
        assertEquals(1, delivered)
    }

    @Test
    fun `clickable が false の円は透過して下のポリゴンへ流れる`() {
        val circle = circleController(clickable = false)
        val polygon = polygonController(clickable = true)

        val hit = OverlayHitResolver.resolve(listOf(circle, polygon), inside)

        assertEquals(OverlayKind.Polygon, hit?.kind)
    }

    @Test
    fun `全部 clickable が false なら誰にも当たらない（地図クリックへ落ちる）`() {
        val circle = circleController(clickable = false)
        val polygon = polygonController(clickable = false)
        assertNull(OverlayHitResolver.resolve(listOf(circle, polygon), inside))
    }

    @Test
    fun `重なった円とポリゴンでは円が勝ち、ポリゴンには配送されない`() {
        var circleClicks = 0
        var polygonClicks = 0
        val circle = circleController(clickable = true) { circleClicks++ }
        val polygon = polygonController(clickable = true) { polygonClicks++ }

        OverlayHitResolver.resolve(listOf(polygon, circle), inside)?.dispatch()

        assertEquals(1, circleClicks)
        assertEquals("二重配送してはいけない", 0, polygonClicks)
    }

    @Test
    fun `グラウンドイメージは円の次、ポリゴンより先`() {
        val groundImage = groundImageController()
        val polygon = polygonController(clickable = true)

        val hit = OverlayHitResolver.resolve(listOf(polygon, groundImage), inside)

        assertEquals(OverlayKind.GroundImage, hit?.kind)
    }

    @Test
    fun `order を差し替えれば探索順を変えられる`() {
        // ネイティブのクリックリスナーを発火のきっかけにしか使わないプロバイダが
        // 順序を絞れるようにしてある（既定から降りられること自体を押さえる）。
        val circle = circleController(clickable = true)
        val polygon = polygonController(clickable = true)

        val hit =
            OverlayHitResolver.resolve(
                listOf(circle, polygon),
                inside,
                order = listOf(OverlayKind.Polygon, OverlayKind.Circle),
            )

        assertEquals(OverlayKind.Polygon, hit?.kind)
    }

    // ── 実物のコントローラ組み立て ──────────────────────────────────────

    private fun circleController(
        clickable: Boolean,
        onClick: () -> Unit = {},
    ): CircleController<Unit> {
        val manager = CircleManager<Unit>()
        val controller =
            object : CircleController<Unit>(manager, FakeCircleRenderer()) {}
        manager.registerEntity(
            CircleEntity(
                circle = Unit,
                state =
                    CircleState(
                        center = inside,
                        radiusMeters = 100_000.0,
                        clickable = clickable,
                        onClick = { onClick() },
                    ),
            ),
        )
        return controller
    }

    private fun polygonController(
        clickable: Boolean,
        onClick: () -> Unit = {},
    ): PolygonController<Unit> {
        val manager = PolygonManager<Unit>()
        val controller =
            object : PolygonController<Unit>(manager, FakePolygonRenderer()) {}
        manager.registerEntity(
            PolygonEntity(
                polygon = Unit,
                state =
                    PolygonState(
                        points =
                            listOf(
                                GeoPoint.fromLatLong(-1.0, -1.0),
                                GeoPoint.fromLatLong(-1.0, 1.0),
                                GeoPoint.fromLatLong(1.0, 1.0),
                                GeoPoint.fromLatLong(1.0, -1.0),
                            ),
                        clickable = clickable,
                        onClick = { onClick() },
                    ),
            ),
        )
        return controller
    }

    private fun groundImageController(): GroundImageController<Unit> {
        val manager = GroundImageManager<Unit>()
        val controller =
            object : GroundImageController<Unit>(manager, FakeGroundImageRenderer()) {}
        val bounds =
            GeoRectBounds().apply {
                extend(GeoPoint.fromLatLong(-1.0, -1.0))
                extend(GeoPoint.fromLatLong(1.0, 1.0))
            }
        manager.registerEntity(
            GroundImageEntity(
                groundImage = Unit,
                state = GroundImageState(bounds = bounds, image = FakeDrawable()),
            ),
        )
        return controller
    }

    // ── フェイク ────────────────────────────────────────────────────────

    private class FakeSlotted(
        override val kind: OverlayKind,
    ) : SlottedOverlayController<String, String> {
        override val zIndex: Int = 0

        override suspend fun add(data: List<String>) = Unit

        override suspend fun update(state: String) = Unit

        override suspend fun clear() = Unit

        override fun find(position: GeoPointInterface): String? = null

        override fun resolveTap(position: GeoPointInterface): OverlayHit? = null

        override fun destroy() = Unit
    }

    private class FakePlain : OverlayControllerInterface<String, String> {
        override val zIndex: Int = 0

        override suspend fun add(data: List<String>) = Unit

        override suspend fun update(state: String) = Unit

        override suspend fun clear() = Unit

        override fun find(position: GeoPointInterface): String? = null

        override fun destroy() = Unit
    }

    private class FakeCircleRenderer : CircleOverlayRendererInterface<Unit> {
        override suspend fun onAdd(data: List<CircleOverlayRendererInterface.AddParamsInterface>): List<Unit?> =
            data.map { null }

        override suspend fun onChange(
            data: List<CircleOverlayRendererInterface.ChangeParamsInterface<Unit>>,
        ): List<Unit?> = data.map { null }

        override suspend fun onRemove(data: List<CircleEntityInterface<Unit>>) = Unit

        override suspend fun onPostProcess() = Unit
    }

    private class FakePolygonRenderer : PolygonOverlayRendererInterface<Unit> {
        override suspend fun onAdd(data: List<PolygonOverlayRendererInterface.AddParamsInterface>): List<Unit?> =
            data.map { null }

        override suspend fun onChange(
            data: List<PolygonOverlayRendererInterface.ChangeParamsInterface<Unit>>,
        ): List<Unit?> = data.map { null }

        override suspend fun onRemove(data: List<PolygonEntityInterface<Unit>>) = Unit

        override suspend fun onPostProcess() = Unit
    }

    private class FakeGroundImageRenderer : GroundImageOverlayRendererInterface<Unit> {
        override suspend fun onAdd(data: List<GroundImageOverlayRendererInterface.AddParamsInterface>): List<Unit?> =
            data.map { null }

        override suspend fun onChange(
            data: List<GroundImageOverlayRendererInterface.ChangeParamsInterface<Unit>>,
        ): List<Unit?> = data.map { null }

        override suspend fun onRemove(data: List<GroundImageEntityInterface<Unit>>) = Unit

        override suspend fun onPostProcess() = Unit
    }

    private class FakeDrawable : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) = Unit

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }
}
