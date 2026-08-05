package com.mapconductor.core.marker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarkerHitTest] の判定境界。
 *
 * ios-sdk の `MarkerHitTestTests`（`ios-sdk-core`）と同じ観点を見る。半径固定の判定に
 * 戻ってしまうと、大きいアイコンは端が反応せず小さいアイコンは離れていても反応する、という
 * 以前の挙動に戻るため、その差が出る点を明示的に押さえる。
 *
 * `ResourceProvider.getDensity()` は Android フレームワークを要するので、密度に依存しない
 * 純粋な幾何判定 [MarkerHitTest.hitsIconRect] を直接叩く（`hitsIcon` はこれに委譲する）。
 */
class MarkerHitTestTest {
    private val tolerance = 14.0 // Settings.Default.tapTolerance (dp) × density 1.0

    /** 既定アンカー `(0.5, 1.0)`（下端中央）のアイコンとして判定する。 */
    private fun hits(
        dx: Double,
        dy: Double,
        iconSize: Double,
    ): Boolean =
        MarkerHitTest.hitsIconRect(
            dx = dx,
            dy = dy,
            iconWidthPx = iconSize,
            iconHeightPx = iconSize,
            anchorX = 0.5,
            anchorY = 1.0,
            tolerancePx = tolerance,
        )

    @Test
    fun anchorPointHits() {
        assertTrue(hits(0.0, 0.0, 48.0))
    }

    @Test
    fun iconExtendsUpwardFromBottomAnchor() {
        // アンカーは下端なので、矩形はアンカーから上へ 48px（+ 許容量）伸びる。
        assertTrue("アイコン上端は当たること", hits(0.0, -48.0, 48.0))
        assertTrue("上端 + 許容量の内側は当たること", hits(0.0, -48.0 - tolerance + 0.5, 48.0))
        assertFalse("上端 + 許容量の外側は外れること", hits(0.0, -48.0 - tolerance - 0.5, 48.0))
    }

    @Test
    fun belowAnchorOnlyToleranceApplies() {
        // アンカーより下にアイコンは無いので、許容量の分しか当たらない。
        assertTrue(hits(0.0, tolerance - 0.5, 48.0))
        assertFalse(hits(0.0, tolerance + 0.5, 48.0))
    }

    @Test
    fun horizontalBoundsUseHalfIconWidth() {
        val limit = 24.0 + tolerance // 半幅 + 許容量
        assertTrue(hits(limit - 0.5, 0.0, 48.0))
        assertFalse(hits(limit + 0.5, 0.0, 48.0))
        assertTrue(hits(-(limit - 0.5), 0.0, 48.0))
    }

    /** 半径固定の判定との差。大きいアイコンは、固定半径より外側でも当たる。 */
    @Test
    fun largeIconHitsBeyondFixedRadius() {
        assertTrue("大きいアイコンの上部は当たること", hits(0.0, -100.0, 120.0))
        assertTrue("大きいアイコンの左右端も当たること", hits(55.0, 0.0, 120.0))
    }

    /** 逆に、小さいアイコンはアイコンから離れた場所では当たらない。 */
    @Test
    fun smallIconDoesNotHitFarAway() {
        assertFalse(hits(0.0, -40.0, 16.0))
        assertFalse(hits(30.0, 0.0, 16.0))
    }

    /** 中央アンカー `(0.5, 0.5)` なら矩形はアンカーの上下へ均等に広がる。 */
    @Test
    fun centerAnchorExpandsBothWays() {
        fun centered(
            dx: Double,
            dy: Double,
        ) = MarkerHitTest.hitsIconRect(
            dx = dx,
            dy = dy,
            iconWidthPx = 48.0,
            iconHeightPx = 48.0,
            anchorX = 0.5,
            anchorY = 0.5,
            tolerancePx = tolerance,
        )
        assertTrue(centered(0.0, -24.0))
        assertTrue(centered(0.0, 24.0))
        assertFalse(centered(0.0, 24.0 + tolerance + 0.5))
    }
}
