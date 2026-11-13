package com.mapconductor.core.marker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The geocell conformance test.
 *
 * ios-sdk and react-sdk read the same `geocell-vectors.txt`. The point is that
 * all three name the same cell; what runs here is only this platform's half.
 *
 * Built like `ProjectionConformanceTest`. What differs is not the shape but
 * **why the comparison is on strings.** The projection returns a Double
 * everywhere, so rounding the digits is enough. A cell key does not: it is a
 * `Long` here, an `Int64` on iOS and a `number` on the web, and that last one
 * holds integers exactly only to 2^53 — its bitwise operators truncate to 32
 * bits entirely. Comparing the numbers could not tell a real disagreement from
 * a difference in representation. Over `0123456789abcdef` the question does not
 * arise.
 */
class GeocellConformanceTest {
    private fun runVectors(): String {
        val vectors =
            requireNotNull(javaClass.classLoader?.getResourceAsStream("geocell-vectors.txt")) {
                "geocell-vectors.txt not on the test classpath"
            }
        val out = StringBuilder()
        vectors.bufferedReader().forEachLine { line ->
            val row = line.trim()
            if (row.isEmpty() || row.startsWith("#")) return@forEachLine
            val parts = row.split("|")
            when (parts[0]) {
                "geocell" -> {
                    val text = MarkerGrid.geocell(parts[1].toDouble(), parts[2].toDouble(), parts[3].toInt())
                    out.append("geocell|${parts[1]}|${parts[2]}|${parts[3]}|$text\n")
                }
                "level" -> {
                    val level = MarkerGrid.levelForSeparation(parts[1].toDouble())
                    out.append("level|${parts[1]}|${level?.toString() ?: "none"}\n")
                }
                "cell" -> {
                    val level = parts[3].toInt()
                    val lat = MarkerGrid.latCell(parts[1].toDouble(), level)
                    val lon = MarkerGrid.lonCell(parts[2].toDouble(), level)
                    out.append("cell|${parts[1]}|${parts[2]}|${parts[3]}|$lat,$lon\n")
                }
                "qlevel" -> {
                    val level = MarkerGrid.queryLevel(parts[1].toDouble(), parts[2].toDouble())
                    out.append("qlevel|${parts[1]}|${parts[2]}|$level\n")
                }
                "colwalk" -> {
                    val level = parts[3].toInt()
                    val span = MarkerGrid.eastwardSpan(parts[1].toDouble(), parts[2].toDouble())
                    val (start, count) = MarkerGrid.columnWalk(parts[1].toDouble(), span, level)
                    out.append("colwalk|${parts[1]}|${parts[2]}|${parts[3]}|$start,$count\n")
                }
                "morton" -> {
                    val key = MarkerGrid.morton(parts[1].toLong(), parts[2].toLong(), parts[3].toInt())
                    out.append("morton|${parts[1]}|${parts[2]}|${parts[3]}|$key\n")
                }
                else -> throw IllegalArgumentException("unknown op: ${parts[0]}")
            }
        }
        return out.toString()
    }

    @Test
    fun vectorsAllRun() {
        val actual = runVectors()

        // Written out for the cross-platform comparison. Not a by-product of
        // the test — this is the product.
        File(System.getProperty("java.io.tmpdir"), "geocell-actual.android.txt").writeText(actual)
        assertTrue("no vector was processed", actual.lines().count { it.isNotBlank() } > 0)
    }

    /** Absent until the three platforms agree and the answer is frozen. */
    @Test
    fun matchesCanonicalIfPresent() {
        val expected = javaClass.classLoader?.getResourceAsStream("geocell-expected.txt")
        assumeTrue("no canonical file yet", expected != null)
        assertEquals(expected!!.bufferedReader().readText(), runVectors())
    }

    /**
     * A deeper cell's name extends a shallower one's.
     *
     * This is the condition that makes the index a hierarchy at all. Break it
     * and "a coarse cell's markers are contiguous" stops holding, and range
     * queries quietly drop markers.
     */
    @Test
    fun deeperCellsExtendShallowerOnes() {
        for ((latitude, longitude) in listOf(35.681236 to 139.767125, -33.86882 to 151.20929, 0.0 to 0.0)) {
            var previous = ""
            for (characters in 1..10) {
                val text = MarkerGrid.geocell(latitude, longitude, characters)
                assertEquals(characters, text.length)
                assertTrue("$text does not extend $previous", text.startsWith(previous))
                previous = text
            }
        }
    }
}
