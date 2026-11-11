package com.mapconductor.core.shapetile

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

class ShapeGridIndexTest {
    @Test fun wideQueriesUseOccupiedGroupsInsteadOfScanningEveryShape() {
        val boxes = List(4096) { i ->
            val x = (i % 64 + 0.5) / 64.0
            val y = (i / 64 + 0.5) / 64.0
            doubleArrayOf(x, y, x + 0.000001, y + 0.000001)
        }
        val index = ShapeGridIndex.build(boxes.size) { boxes[it] }
        var inspected = 0
        val actual = index.query({ inspected++; boxes[it] }, 0.1, 0.1, 0.1625, 0.1625)
        val expected = boxes.indices.filter { i ->
            val b = boxes[i]
            b[0] <= 0.1625 && b[2] >= 0.1 && b[1] <= 0.1625 && b[3] >= 0.1
        }.toIntArray()
        assertArrayEquals(expected, actual)
        org.junit.Assert.assertTrue("inspected $inspected of ${boxes.size}", inspected < boxes.size / 4)
    }

    @Test fun mixedSizesMatchBruteForceWithoutDuplicatesInDrawOrder() {
        val random = Random(307)
        val boxes =
            List(4000) { i ->
                if (i % 17 == 0) {
                    null
                } else {
                    val x = random.nextDouble(-0.05, 1.05)
                    val y = random.nextDouble(0.0, 1.0)
                    val width = listOf(0.00001, 0.001, 0.01, 0.25)[i % 4]
                    doubleArrayOf(x, y, x + width, y + width)
                }
            }
        val index = ShapeGridIndex.build(boxes.size) { boxes[it] }
        for (z in listOf(0, 2, 4, 8, 12, 18)) {
            repeat(30) {
                val x = random.nextDouble(-0.1, 1.1)
                val y = random.nextDouble(-0.1, 1.1)
                val size = 1.0 / (1 shl z)
                val expected =
                    boxes.indices
                        .filter { i ->
                            boxes[i]?.let { b -> b[0] <= x + size && b[2] >= x && b[1] <= y + size && b[3] >= y } ==
                                true
                        }.toIntArray()
                assertArrayEquals("zoom=$z", expected, index.query({ boxes[it] }, x, y, x + size, y + size))
            }
        }
    }
}
