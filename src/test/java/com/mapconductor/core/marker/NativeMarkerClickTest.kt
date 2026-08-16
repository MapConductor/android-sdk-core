package com.mapconductor.core.marker

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dispatchNativeMarkerClick] の意味論テスト。
 *
 * android-for-googlemaps と android-for-tomtom が同じ判断を二重に持っていたものを
 * コアへ移したもの。守りたい不変条件は **MapConductor 管理外のマーカーを横取りしない**
 * こと。`mapViewState.getMapViewHolder().map` でネイティブの地図をアプリへ公開して
 * いるため、他ライブラリが追加したマーカーが混ざりうる。それらは false を返して
 * ネイティブの既定動作へ委ねなければならない。
 */
@OptIn(InternalMapConductorApi::class)
class NativeMarkerClickTest {
    private class FakeController : NativeMarkerClickTargetInterface<Any> {
        val entities = mutableMapOf<String, MarkerEntityInterface<Any>>()
        val dispatched = mutableListOf<String>()

        override fun getEntity(id: String): MarkerEntityInterface<Any>? = entities[id]

        override fun dispatchClick(state: MarkerState) {
            dispatched += state.id
        }

        fun add(
            id: String,
            clickable: Boolean = true,
        ) {
            entities[id] =
                MarkerEntity(
                    marker = null,
                    state =
                        MarkerState(
                            id = id,
                            position = GeoPoint.fromLatLong(0.0, 0.0),
                            clickable = clickable,
                        ),
                )
        }
    }

    // ── 管理外のマーカーは素通しする ────────────────────────────────────

    @Test
    fun `tag が null なら消費しない`() {
        val controller = FakeController().apply { add("a") }
        assertFalse(listOf(controller).dispatchNativeMarkerClick(null))
        assertTrue(controller.dispatched.isEmpty())
    }

    @Test
    fun `tag が String でなければ消費しない`() {
        val controller = FakeController().apply { add("a") }
        // アプリが直接 map.addMarker した場合、tag には任意の型が入りうる。
        assertFalse(listOf(controller).dispatchNativeMarkerClick(42))
        assertFalse(listOf(controller).dispatchNativeMarkerClick(Any()))
        assertTrue(controller.dispatched.isEmpty())
    }

    @Test
    fun `見覚えのない id は消費しない`() {
        val controller = FakeController().apply { add("a") }
        assertFalse(listOf(controller).dispatchNativeMarkerClick("unknown"))
        assertTrue(controller.dispatched.isEmpty())
    }

    @Test
    fun `コントローラが空でも消費しない`() {
        assertFalse(emptyList<NativeMarkerClickTargetInterface<Any>>().dispatchNativeMarkerClick("a"))
    }

    // ── 管理下のマーカー ────────────────────────────────────────────────

    @Test
    fun `clickable なマーカーは配送して消費する`() {
        val controller = FakeController().apply { add("a") }
        assertTrue(listOf(controller).dispatchNativeMarkerClick("a"))
        assertEquals(listOf("a"), controller.dispatched)
    }

    @Test
    fun `clickable が false なら配送せずに消費する`() {
        val controller = FakeController().apply { add("a", clickable = false) }
        // 消費する（true）ので、ネイティブの既定動作（情報ウィンドウ＋カメラ移動）は起きない。
        assertTrue(listOf(controller).dispatchNativeMarkerClick("a"))
        assertTrue(controller.dispatched.isEmpty())
    }

    // ── 複数コントローラ（クラスタリングで増える） ──────────────────────

    @Test
    fun `最初に見つけたコントローラだけが配送する`() {
        val first = FakeController()
        val second = FakeController().apply { add("a") }
        val third = FakeController().apply { add("a") }

        assertTrue(listOf(first, second, third).dispatchNativeMarkerClick("a"))

        assertTrue(first.dispatched.isEmpty())
        assertEquals(listOf("a"), second.dispatched)
        assertTrue("2 つ目で確定したら以降は見ない", third.dispatched.isEmpty())
    }

    @Test
    fun `知らないコントローラは飛ばして次を見る`() {
        val first = FakeController().apply { add("other") }
        val second = FakeController().apply { add("a") }

        assertTrue(listOf(first, second).dispatchNativeMarkerClick("a"))

        assertTrue(first.dispatched.isEmpty())
        assertEquals(listOf("a"), second.dispatched)
    }
}
