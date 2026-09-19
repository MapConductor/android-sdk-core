package com.mapconductor.core.projection

import com.mapconductor.core.features.GeoPoint
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web Mercator の適合テスト。
 *
 * 同じ `projection-vectors.txt` を ios-sdk / react-sdk も読む。3 つが同じ答えを
 * 出すことを担保するのが目的で、ここが見ているのはこのプラットフォームの分だけ。
 * 突き合わせは 3 つの出力に対して別途行う。
 *
 * `zoom-golden.txt` とは役割が違う。あちらは移行前の値の記録（既知の不具合込み）。
 * こちらは現在の実装どうしが一致しているかを見る。
 */
class ProjectionConformanceTest {
    /**
     * 桁を固定する。
     *
     * 言語ごとの既定の数値表記は揃わない。Kotlin の `Double.toString()` は
     * 2000 万台を `2.0037508342789E7` と書くが、JS と Swift は書かない。
     * f64 のノイズより粗く、実装差より細かい桁で切る。
     */
    private fun fixed(value: Double): String =
        when {
            value.isNaN() -> "nan"
            value == Double.POSITIVE_INFINITY -> "inf"
            value == Double.NEGATIVE_INFINITY -> "-inf"
            else -> String.format(java.util.Locale.ROOT, "%.6f", value)
        }

    private fun runVectors(): String {
        val vectors =
            requireNotNull(javaClass.classLoader?.getResourceAsStream("projection-vectors.txt")) {
                "projection-vectors.txt が見つからない"
            }.bufferedReader().readText()

        val out = StringBuilder()
        for (line in vectors.lines()) {
            val row = line.trim()
            if (row.isEmpty() || row.startsWith("#")) continue
            val (op, a, b) = row.split("|").let { Triple(it[0], it[1], it[2]) }
            when (op) {
                "project" -> {
                    // 実物の GeoPoint を使う。無名実装だと wrap() を握り潰して
                    // しまい、投影側の wrap が効いていないように見える。
                    val p = WebMercator.project(GeoPoint(a.toDouble(), b.toDouble(), 0.0))
                    out.append("project|$a|$b|${fixed(p.x)}|${fixed(p.y)}\n")
                }
                "unproject" -> {
                    val g = WebMercator.unproject(ProjectedPoint(a.toDouble(), b.toDouble()))
                    out.append("unproject|$a|$b|${fixed(g.latitude)}|${fixed(g.longitude)}\n")
                }
                else -> error("未知の演算: $op")
            }
        }
        return out.toString()
    }

    @Test
    fun `ベクタを 1 行残らず処理できる`() {
        val actual = runVectors()

        // 突き合わせに使う。テストの副産物ではなく、これが成果物。
        val destination = File("build/conformance/projection-actual.android.txt")
        destination.parentFile?.mkdirs()
        destination.writeText(actual)

        val rows = actual.trimEnd().lines()
        assertTrue("ベクタが 1 行も処理されていない", rows.isNotEmpty())
        for (row in rows) {
            assertEquals("列数が合わない: $row", 5, row.split("|").size)
        }
    }

    /** 正本が決まるまでは存在しない。3 プラットフォームの差を潰してから凍結する。 */
    @Test
    fun `正本があれば完全一致する`() {
        val expected =
            javaClass.classLoader?.getResourceAsStream("projection-expected.txt")
                ?.bufferedReader()?.readText() ?: return
        assertEquals(expected, runVectors())
    }
}
