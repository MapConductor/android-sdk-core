package com.mapconductor.core.marker

import com.mapconductor.core.marker.MarkerViewportSwitch.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [MarkerViewportSwitch.decide] の切り替え境界。
 *
 * ここが単独でテストできるように、判定は副作用の無い関数に切り出してある（レンダラも
 * マネージャも Android フレームワークを要するため、実際の昇格処理は単体テストで回せない）。
 *
 * 見るのは主にヒステリシス。上下を同じ閾値にすると、境界付近をパンするたびに数百件の
 * add/remove が往復するので、そこが崩れていないことを押さえる。
 */
class MarkerViewportSwitchTest {
    private val policy =
        MarkerViewportPolicy(
            nativeMaxCount = 500,
            tileMinCount = 600,
            minZoom = 0.0,
        )

    private fun decide(
        current: Mode,
        nearCount: Int,
        zoom: Double = 16.0,
        policy: MarkerViewportPolicy = this.policy,
    ): Mode = MarkerViewportSwitch.decide(current = current, policy = policy, zoom = zoom, nearCount = nearCount)

    @Test
    fun `タイル中は nativeMaxCount 以下でネイティブへ`() {
        assertEquals(Mode.Native, decide(Mode.Tile, nearCount = 0))
        assertEquals(Mode.Native, decide(Mode.Tile, nearCount = 500))
        assertEquals(Mode.Tile, decide(Mode.Tile, nearCount = 501))
    }

    @Test
    fun `ネイティブ中は tileMinCount 以上までタイルへ戻らない`() {
        assertEquals(Mode.Native, decide(Mode.Native, nearCount = 501))
        assertEquals(Mode.Native, decide(Mode.Native, nearCount = 599))
        assertEquals(Mode.Tile, decide(Mode.Native, nearCount = 600))
    }

    /** 501〜599 は「現状維持」でなければならない。ここが崩れると境界で往復する。 */
    @Test
    fun `ヒステリシス帯では現状のモードを保つ`() {
        for (count in 501..599) {
            assertEquals("count=$count", Mode.Tile, decide(Mode.Tile, nearCount = count))
            assertEquals("count=$count", Mode.Native, decide(Mode.Native, nearCount = count))
        }
    }

    @Test
    fun `minZoom より引いていたら件数によらずタイル`() {
        val zoomed = policy.copy(minZoom = 12.0)
        assertEquals(Mode.Tile, decide(Mode.Tile, nearCount = 0, zoom = 11.9, policy = zoomed))
        assertEquals(Mode.Tile, decide(Mode.Native, nearCount = 0, zoom = 11.9, policy = zoomed))
        assertEquals(Mode.Native, decide(Mode.Tile, nearCount = 0, zoom = 12.0, policy = zoomed))
    }

    @Test
    fun `無効なら常にタイル`() {
        assertEquals(Mode.Tile, decide(Mode.Tile, nearCount = 0, policy = MarkerViewportPolicy.Disabled))
        assertEquals(Mode.Tile, decide(Mode.Native, nearCount = 0, policy = MarkerViewportPolicy.Disabled))
    }

    /** 上下同値のポリシーは作れてはいけない（作れるとヒステリシスが消える）。 */
    @Test
    fun `tileMinCount が nativeMaxCount 以下なら生成できない`() {
        assertThrows(IllegalArgumentException::class.java) {
            MarkerViewportPolicy(nativeMaxCount = 500, tileMinCount = 500)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MarkerViewportPolicy(nativeMaxCount = 500, tileMinCount = 499)
        }
    }

    @Test
    fun `既定値はヒステリシスを持つ`() {
        assertEquals(500, MarkerViewportPolicy.Default.nativeMaxCount)
        assertEquals(true, MarkerViewportPolicy.Default.tileMinCount > MarkerViewportPolicy.Default.nativeMaxCount)
    }
}
