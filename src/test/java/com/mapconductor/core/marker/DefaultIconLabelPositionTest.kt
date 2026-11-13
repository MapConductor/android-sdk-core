package com.mapconductor.core.marker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `labelPosition` の読み取り。react-sdk の `DefaultIconLabelPosition.test.mjs`、
 * ios-sdk の `DefaultIconLabelPositionTests` と同じ規則を見る。
 */
class DefaultIconLabelPositionTest {
    private fun parse(value: String) = AbstractDefaultIcon.parseLabelPosition(value)

    @Test
    fun defaultIsCenterCenter() {
        assertEquals(0 to 0, parse(AbstractDefaultIcon.DEFAULT_LABEL_POSITION))
    }

    @Test
    fun readsBothAxes() {
        assertEquals(1 to 0, parse("right center"))
        assertEquals(-1 to -1, parse("left top"))
        assertEquals(0 to 1, parse("center bottom"))
    }

    @Test
    fun keywordOrderDoesNotMatter() {
        assertEquals(parse("right top"), parse("top right"))
    }

    @Test
    fun singleKeywordLeavesOtherAxisCentered() {
        assertEquals(1 to 0, parse("right"))
        assertEquals(0 to -1, parse("top"))
    }

    @Test
    fun unknownWordsFallBackToCenter() {
        assertEquals(0 to 0, parse("  middle  "))
        assertEquals(1 to 1, parse(" RIGHT   Bottom "))
    }
}
