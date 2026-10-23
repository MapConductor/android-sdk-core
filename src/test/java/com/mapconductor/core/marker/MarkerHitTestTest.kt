package com.mapconductor.core.marker

import androidx.compose.ui.geometry.Offset
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

/**
 * [MarkerHitPick.pickIndex] の選び方。ios-sdk の `MarkerHitTestTests`、react-sdk の
 * `MarkerHitPick.test.mjs` と同じ観点を見る。
 */
@OptIn(com.mapconductor.core.InternalMapConductorApi::class)
class MarkerHitTestPickTest {
    private val tolerance = 14.0

    private fun rect(
        x: Float,
        y: Float,
        width: Double = 48.0,
        height: Double = 48.0,
        anchorX: Double = 0.5,
    ) = MarkerHitPick.ScreenRect(Offset(x, y), width, height, anchorX, 1.0)

    @Test
    fun iconUnderTheFingerBeatsANearerTip() {
        // labelled: 先端 (0, 100)、ラベルを右へ伸ばして幅 200（矩形 x -24..176, y 52..100）。
        // nearTip:  先端 (150, 70) でタップ点にずっと近いが、矩形 (126..174, 22..70) はタップ点に届かない。
        // タップ (150, 80) は labelled のラベルの上なので、先端の近さに関係なく labelled が選ばれる。
        val labelled = rect(0f, 100f, width = 200.0, anchorX = 0.12)
        val nearTip = rect(150f, 70f)
        assertTrue(MarkerHitPick.pickIndex(Offset(150f, 80f), listOf(nearTip, labelled), tolerance) == 1)
    }

    @Test
    fun frontmostWinsWhenTwoIconsOverlap() {
        val back = rect(100f, 100f)
        val front = rect(100f, 120f)
        assertTrue(MarkerHitPick.pickIndex(Offset(100f, 90f), listOf(back, front), tolerance) == 1)
    }

    @Test
    fun toleranceOnlyWhenNothingIsUnderTheFinger() {
        val far = rect(100f, 100f)
        val near = rect(140f, 100f)
        // どちらの矩形にも入らないが、許容範囲には両方入る点。アンカーの近い方。
        val touch = Offset(170f, 105f)
        assertTrue(MarkerHitPick.pickIndex(touch, listOf(far, near), 40.0) == 1)
        assertTrue(MarkerHitPick.pickIndex(Offset(400f, 400f), listOf(far, near), tolerance) == null)
    }
}
