package com.mapconductor.core

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.marker.MarkerEntity
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.clickableOnly
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.PolylineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `clickable` の意味論テスト。
 *
 * **`clickable = false` は「透過」**。そのオーバーレイはタップを受け取らず、
 * 下のオーバーレイ（無ければ地図クリック）へイベントが流れる。CircleState が
 * 元々そう振る舞っており、Polygon / Polyline / GroundImage / Marker をそこへ揃えた。
 *
 * 判定の置き場所が種類で違う点に注意:
 *  - Polygon / Polyline / Circle / GroundImage … 各 Manager の `find` で除外する。
 *  - Marker … `find` ではなく **クリック配送側**（`dispatchClick`）で止める。
 *    `find` はドラッグの開始判定にも使われるため、そこで除外すると
 *    `clickable=false` かつ `draggable=true` のマーカーがドラッグ不能になる。
 *
 * Manager 側の `find` は Android フレームワーク（ResourceProvider の density 等）に
 * 依存するものがあり素の JVM テストでは動かないので、ここでは
 * **状態の既定値・保持・copy の伝播**と、**マーカーのクリック配送のゲート**を押さえる。
 */
class OverlayClickableTest {
    private val p1 = GeoPoint.fromLatLong(0.0, 0.0)
    private val p2 = GeoPoint.fromLatLong(1.0, 0.0)
    private val p3 = GeoPoint.fromLatLong(1.0, 1.0)

    // ── 既定値は true（追加前と同じ挙動） ────────────────────────────────

    @Test
    fun `clickable の既定値は true`() {
        assertTrue(PolygonState(points = listOf(p1, p2, p3)).clickable)
        assertTrue(PolylineState(points = listOf(p1, p2)).clickable)
        assertTrue(MarkerState(position = p1).clickable)
    }

    // ── 保持と copy ─────────────────────────────────────────────────────

    @Test
    fun `PolygonState は clickable を保持し copy で引き継ぐ`() {
        val state = PolygonState(points = listOf(p1, p2, p3), clickable = false)
        assertFalse(state.clickable)
        assertFalse(state.copy().clickable)
        assertTrue(state.copy(clickable = true).clickable)
    }

    @Test
    fun `PolylineState は clickable を保持し copy で引き継ぐ`() {
        val state = PolylineState(points = listOf(p1, p2), clickable = false)
        assertFalse(state.clickable)
        assertFalse(state.copy().clickable)
        assertTrue(state.copy(clickable = true).clickable)
    }

    @Test
    fun `clickable は後から変更できる`() {
        val state = PolygonState(points = listOf(p1, p2, p3))
        state.clickable = false
        assertFalse(state.clickable)
    }

    // ── 自動生成 id は clickable を含む ──────────────────────────────────

    @Test
    fun `clickable が違えば自動生成 id も違う`() {
        val a = PolygonState(points = listOf(p1, p2, p3), clickable = true)
        val b = PolygonState(points = listOf(p1, p2, p3), clickable = false)
        assertFalse("clickable が id に反映されていない", a.id == b.id)

        val c = PolylineState(points = listOf(p1, p2), clickable = true)
        val d = PolylineState(points = listOf(p1, p2), clickable = false)
        assertFalse(c.id == d.id)
    }

    // ── fingerPrint には含めない ────────────────────────────────────────

    @Test
    fun `clickable の変更は fingerPrint を変えない`() {
        // fingerPrint は再描画のトリガー。clickable は描画に影響しないので含めない
        // （含めると値を変えるたびにオーバーレイが作り直される）。ヒットテストは
        // state.clickable を都度読むので、含めなくても判定は即座に反映される。
        val state = PolygonState(points = listOf(p1, p2, p3))
        val before = state.fingerPrint()
        state.clickable = false
        assertEquals(before, state.fingerPrint())

        val line = PolylineState(points = listOf(p1, p2))
        val lineBefore = line.fingerPrint()
        line.clickable = false
        assertEquals(lineBefore, line.fingerPrint())
    }

    @Test
    fun `GroundImageState の fingerPrint も clickable を含まない`() {
        val bounds =
            GeoRectBounds().apply {
                extend(p1)
                extend(p3)
            }
        val state = GroundImageState(bounds = bounds, image = FakeDrawable(), clickable = true)
        val before = state.fingerPrint()
        state.clickable = false
        assertEquals(before, state.fingerPrint())
        assertFalse(state.copy().clickable)
    }

    // ── マーカーのクリック配送ゲート ────────────────────────────────────

    @Test
    fun `clickable が false のマーカーには onClick が飛ばない`() {
        var called = 0
        val controller = FakeMarkerController()

        controller.dispatchClick(MarkerState(position = p1, clickable = true, onClick = { called++ }))
        assertEquals(1, called)

        controller.dispatchClick(MarkerState(position = p1, clickable = false, onClick = { called++ }))
        assertEquals("clickable=false には配送しない", 1, called)
    }

    @Test
    fun `clickable が false のマーカーはリスナーにも飛ばない`() {
        var listened = 0
        val controller = FakeMarkerController().apply { clickListener = { listened++ } }

        controller.dispatchClick(MarkerState(position = p1, clickable = false))
        assertEquals(0, listened)

        controller.dispatchClick(MarkerState(position = p1, clickable = true))
        assertEquals(1, listened)
    }

    /** `dispatchClick` のゲートだけを取り出した最小の代役。 */
    private class FakeMarkerController {
        var clickListener: ((MarkerState) -> Unit)? = null

        fun dispatchClick(state: MarkerState) {
            if (!state.clickable) return
            state.onClick?.invoke(state)
            clickListener?.invoke(state)
        }
    }

    /** GroundImageState は Drawable を要求するが、テストでは中身を使わない。 */
    private class FakeDrawable : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) = Unit

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    // ── clickableOnly（クリック経路の透過） ──────────────────────────────

    @Test
    fun `clickableOnly は clickable=false を落とす`() {
        val clickable = entityOf(clickable = true)
        val notClickable = entityOf(clickable = false)

        assertSame(clickable, clickable.clickableOnly())
        assertNull(notClickable.clickableOnly())
        assertNull(null.clickableOnly<Any>())
    }

    @Test
    fun `clickableOnly を通せばカスケードは次の層へ進める`() {
        // find() が返したエンティティで打ち切ると、配送されないのにカスケードが
        // 止まり、地図クリックも飛ばない（＝握り潰し）。clickableOnly を通すことで
        // 「当たらなかった」ことになり、次の層へ進める。
        var reachedNextLayer = false
        val hit: MarkerEntityInterface<Any>? = entityOf(clickable = false)

        hit.clickableOnly()?.let { return@let } ?: run { reachedNextLayer = true }

        assertTrue("clickable=false は透過して次の層へ進むべき", reachedNextLayer)
    }

    private fun entityOf(clickable: Boolean): MarkerEntityInterface<Any> =
        MarkerEntity(
            marker = null,
            state =
                MarkerState(
                    position = p1,
                    clickable = clickable,
                ),
        )
}
