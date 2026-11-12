package com.mapconductor.core.marker

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log

/**
 * アンカーがピンの先端に乗っているか。`scale` で縮めても `iconSize` で縮めても同じ。
 *
 * 先端は、アンカー列で一番下にある不透明な画素。アンカーの y との差を px で測る。
 */
@RunWith(AndroidJUnit4::class)
class DefaultIconAnchorDeviceTest {
    private fun tipGapPx(icon: ColorDefaultIcon): Float {
        val bitmapIcon = icon.toBitmapIcon()
        val bitmap = bitmapIcon.bitmap
        val anchorX = (bitmapIcon.anchor.x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val anchorY = bitmapIcon.anchor.y * bitmap.height
        var lowest = -1
        for (y in 0 until bitmap.height) {
            for (dx in -1..1) {
                val x = (anchorX + dx).coerceIn(0, bitmap.width - 1)
                if ((bitmap.getPixel(x, y) ushr 24) > 128) lowest = y
            }
        }
        val gap = anchorY - (lowest + 1)
        Log.i(
            "IconAnchor",
            "${bitmap.width}x${bitmap.height} anchor=${bitmapIcon.anchor} tipBottom=${lowest + 1} gap=$gap",
        )
        return gap
    }

    @Test
    fun anchorSitsOnTheTipWhateverTheSize() {
        val icons =
            listOf(
                ColorDefaultIcon(fillColor = Color.Red),
                ColorDefaultIcon(fillColor = Color.Red, label = "T"),
                ColorDefaultIcon(fillColor = Color.Blue, label = "P", scale = 0.6f),
                ColorDefaultIcon(fillColor = Color.Blue, label = "P", iconSize = 28.dp),
                ColorDefaultIcon(fillColor = Color.Blue, scale = 0.6f),
                ColorDefaultIcon(fillColor = Color.Blue, scale = 2f),
            )
        val gaps = icons.map { tipGapPx(it) / it.toBitmapIcon().bitmap.height }
        // 素のピン（1 番目）の先端とアンカーの差を基準に、どの大きさでも同じ割合であること。
        for (gap in gaps) {
            assertTrue("gaps $gaps", kotlin.math.abs(gap - gaps[0]) <= 0.02f)
        }
    }
}
