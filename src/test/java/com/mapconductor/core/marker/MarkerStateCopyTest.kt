package com.mapconductor.core.marker

import com.mapconductor.core.features.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `MarkerState.copy()` はすべてのプロパティを引き継ぐ。
 *
 * animation は以前 copy に引数が無く、新しいインスタンスは常に animation なしで
 * 作られていた（`marker.copy(position = p)` で跳ねているマーカーが止まる）。
 * コピーであることは「アニメーションをやめる」意味を持たないので引き継ぐ。
 * ios-sdk / react-sdk と同じ意味論であることをここで固定する。
 */
class MarkerStateCopyTest {
    private fun state(animation: MarkerAnimation? = null) =
        MarkerState(
            position = GeoPoint(35.0, 139.0),
            id = "m1",
            animation = animation,
        )

    @Test
    fun copy_carriesAnimationOver() {
        val original = state(MarkerAnimation.Bounce)
        val copied = original.copy(position = GeoPoint(36.0, 140.0))

        assertEquals(MarkerAnimation.Bounce, copied.getAnimation())
    }

    @Test
    fun copy_canOverrideAnimation() {
        val original = state(MarkerAnimation.Bounce)

        assertEquals(MarkerAnimation.Drop, original.copy(animation = MarkerAnimation.Drop).getAnimation())
        assertNull(original.copy(animation = null).getAnimation())
    }

    @Test
    fun copy_keepsAnimationSetAfterConstruction() {
        // animate() で後から付けた場合も引き継ぐ（コンストラクタ引数だけを見ない）
        val original = state()
        original.animate(MarkerAnimation.Drop)

        assertEquals(MarkerAnimation.Drop, original.copy().getAnimation())
    }

    @Test
    fun copy_keepsOtherProperties() {
        val original =
            MarkerState(
                position = GeoPoint(35.0, 139.0),
                id = "m1",
                animation = MarkerAnimation.Bounce,
                zIndex = 7,
                clickable = false,
                draggable = true,
            )
        val copied = original.copy()

        assertEquals("m1", copied.id)
        assertEquals(7, copied.zIndex)
        assertEquals(false, copied.clickable)
        assertEquals(true, copied.draggable)
        assertEquals(MarkerAnimation.Bounce, copied.getAnimation())
    }
}
