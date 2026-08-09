package com.mapconductor.core.map

import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapViewState] 基底のカメラ意味論テスト。
 *
 * 8 プロバイダが同じ 40 行前後（カメラ保持 / moveCameraTo x2 / fitBounds /
 * getMapViewHolder の委譲）を各自持っていたものをコアへ引き上げた。挙動を
 * ここで固定しておかないと、プロバイダを移行するときに何と一致させるべきかが
 * 分からなくなる。
 *
 * 押さえたい不変条件:
 *  - コントローラ未接続でも [MapViewState.moveCameraTo] が値を失わない。
 *    接続時にその位置へ移動する。
 *  - `durationMillis` の分岐（null / 0 は moveCamera、正なら animateCamera）。
 *  - 初期カメラ移動を**しない**プロバイダ（ArcGIS / MapTiler / Longdo）のための
 *    `moveToInitialCamera = false`。
 */
class MapViewStateBaseTest {
    private class FakeController : MapViewControllerInterface {
        override val holder: MapViewHolderInterface<*, *> = FakeHolder
        val moved = mutableListOf<MapCameraPosition>()
        val animated = mutableListOf<Pair<MapCameraPosition, Long>>()
        val fitted = mutableListOf<Pair<GeoRectBounds, Int>>()

        override suspend fun clearOverlays() = Unit

        override fun moveCamera(position: MapCameraPosition) {
            moved += position
        }

        override fun animateCamera(
            position: MapCameraPosition,
            durationMillis: Long,
        ) {
            animated += position to durationMillis
        }

        override fun fitBounds(
            bounds: GeoRectBounds,
            padding: Int,
        ) {
            fitted += bounds to padding
        }

        override fun registerOverlayController(controller: OverlayControllerInterface<*, *>) = Unit

        override fun destroy() = Unit
    }

    private object FakeHolder : MapViewHolderInterface<Any, Any> {
        override val mapView: Any = Any()
        override val map: Any = Any()

        override fun toScreenOffset(position: com.mapconductor.core.features.GeoPointInterface) = null

        override suspend fun fromScreenOffset(offset: androidx.compose.ui.geometry.Offset): GeoPoint? = null
    }

    /** 基底の protected な口をテストから叩くための最小のサブクラス。 */
    private class TestState(
        initial: MapCameraPosition = MapCameraPosition.Default,
        optimistic: Boolean = false,
    ) : MapViewState<String>(initial, optimistic) {
        override val id: String = "test"
        override var mapDesignType: String = "design"

        fun attach(
            controller: MapViewControllerInterface,
            moveToInitialCamera: Boolean = true,
        ) = attachController(controller, moveToInitialCamera)

        fun detach() = detachController()

        fun pushCamera(cameraPosition: MapCameraPosition) = setCameraPositionInternal(cameraPosition)
    }

    private fun cameraAt(
        lat: Double,
        lng: Double,
        zoom: Double = 10.0,
    ) = MapCameraPosition(position = GeoPoint.fromLatLong(lat, lng), zoom = zoom)

    // ── コントローラ未接続 ──────────────────────────────────────────────

    @Test
    fun `未接続なら moveCameraTo は値を保持するだけ`() {
        val state = TestState()
        state.moveCameraTo(cameraAt(35.0, 139.0), durationMillis = 0)
        val camera = state.cameraPosition
        assertEquals(35.0, camera.position.latitude, 1e-9)
        assertEquals(139.0, camera.position.longitude, 1e-9)
    }

    @Test
    fun `接続時に保持していたカメラ位置へ移動する`() {
        val state = TestState()
        state.moveCameraTo(cameraAt(35.0, 139.0), durationMillis = 0)

        val controller = FakeController()
        state.attach(controller)

        assertEquals(1, controller.moved.size)
        val sent = controller.moved.single()
        assertEquals(35.0, sent.position.latitude, 1e-9)
    }

    @Test
    fun `moveToInitialCamera が false なら接続時に動かさない`() {
        // ArcGIS は Scene のロード中に viewpointChanged が zoom~0 で発火して
        // 初期カメラを上書きするため、接続時のカメラ移動を避けている。
        val state = TestState(cameraAt(35.0, 139.0))
        val controller = FakeController()
        state.attach(controller, moveToInitialCamera = false)
        assertTrue(controller.moved.isEmpty())
    }

    @Test
    fun `未接続なら fitBounds は何もしない`() {
        TestState().fitBounds(GeoRectBounds(), padding = 10)
        // 例外が飛ばなければよい
    }

    @Test
    fun `未接続なら getMapViewHolder は null`() {
        assertNull(TestState().getMapViewHolder())
    }

    // ── 接続後 ──────────────────────────────────────────────────────────

    @Test
    fun `durationMillis が null か 0 なら moveCamera`() {
        val controller = FakeController()
        val state = TestState().apply { attach(controller) }
        controller.moved.clear()

        state.moveCameraTo(cameraAt(1.0, 2.0), durationMillis = null)
        state.moveCameraTo(cameraAt(3.0, 4.0), durationMillis = 0)

        assertEquals(2, controller.moved.size)
        assertTrue(controller.animated.isEmpty())
    }

    @Test
    fun `durationMillis が正なら animateCamera`() {
        val controller = FakeController()
        val state = TestState().apply { attach(controller) }
        controller.moved.clear()

        state.moveCameraTo(cameraAt(1.0, 2.0), durationMillis = 300)

        assertTrue(controller.moved.isEmpty())
        val animated = controller.animated.single()
        assertEquals(300L, animated.second)
    }

    @Test
    fun `位置だけの moveCameraTo は現在のズームなどを保つ`() {
        val controller = FakeController()
        val state = TestState().apply { attach(controller) }
        state.pushCamera(cameraAt(0.0, 0.0, zoom = 14.0))
        controller.moved.clear()

        state.moveCameraTo(GeoPoint.fromLatLong(35.0, 139.0), durationMillis = 0)

        val sent = controller.moved.single()
        assertEquals(35.0, sent.position.latitude, 1e-9)
        assertEquals("ズームは維持される", 14.0, sent.zoom, 1e-9)
    }

    @Test
    fun `fitBounds はコントローラへ委譲する`() {
        val controller = FakeController()
        val state = TestState().apply { attach(controller) }
        val bounds = GeoRectBounds()

        state.fitBounds(bounds, padding = 24)

        val fitted = controller.fitted.single()
        assertSame(bounds, fitted.first)
        assertEquals(24, fitted.second)
    }

    @Test
    fun `getMapViewHolder はコントローラのホルダーを返す`() {
        val state = TestState().apply { attach(FakeController()) }
        assertSame(FakeHolder, state.getMapViewHolder())
    }

    @Test
    fun `setCameraPositionInternal は地図を動かさずに値だけ更新する`() {
        val controller = FakeController()
        val state = TestState().apply { attach(controller) }
        controller.moved.clear()

        state.pushCamera(cameraAt(10.0, 20.0))

        assertEquals(10.0, state.cameraPosition.position.latitude, 1e-9)
        assertTrue("地図を動かしてはいけない", controller.moved.isEmpty())
    }

    // ── 楽観更新（MapTiler / Longdo） ────────────────────────────────────

    @Test
    fun `既定では moveCameraTo 直後に cameraPosition は変わらない`() {
        // ネイティブ SDK はカメライベントを確実に返すので、実際に適用された値だけを
        // state に入れる。要求値で先走らない。
        val state = TestState().apply { attach(FakeController()) }
        state.pushCamera(cameraAt(0.0, 0.0))

        state.moveCameraTo(cameraAt(35.0, 139.0), durationMillis = 0)

        assertEquals(0.0, state.cameraPosition.position.latitude, 1e-9)
    }

    @Test
    fun `optimistic なら moveCameraTo 直後に cameraPosition が更新される`() {
        // WebView ブリッジ越し（MapTiler / Longdo）はイベントの往復が遅く、
        // 要求直後に読むと古い値が返ってしまう。
        val state = TestState(optimistic = true).apply { attach(FakeController()) }
        state.pushCamera(cameraAt(0.0, 0.0))

        state.moveCameraTo(cameraAt(35.0, 139.0), durationMillis = 0)

        assertEquals(35.0, state.cameraPosition.position.latitude, 1e-9)
    }

    @Test
    fun `optimistic でもコントローラへの委譲は変わらない`() {
        val controller = FakeController()
        val state = TestState(optimistic = true).apply { attach(controller) }
        controller.moved.clear()

        state.moveCameraTo(cameraAt(1.0, 2.0), durationMillis = 300)

        assertTrue(controller.moved.isEmpty())
        assertEquals(300L, controller.animated.single().second)
    }

    @Test
    fun `detach 後は委譲しない`() {
        val controller = FakeController()
        val state = TestState().apply { attach(controller) }
        controller.moved.clear()

        state.detach()
        state.moveCameraTo(cameraAt(1.0, 2.0), durationMillis = 0)

        assertTrue(controller.moved.isEmpty())
        assertNull(state.getMapViewHolder())
    }
}
