package com.mapconductor.core.polygon

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * unionHoleRings keeps the area of the union of the holes: holes that touch at a
 * point or share (part of) an edge add up, overlaps count once.
 *
 * js-sdk-core (`PolygonHoleUnion.test.mjs`) and ios-sdk-core
 * (`PolygonHoleUnionFixtureTests`) read the same `polygon-hole-union-fixture.txt`;
 * the expected areas are shapely's unary_union.
 */
class PolygonHoleUnionFixtureTest {
    private class Case(
        val name: String,
        val unit: Double,
        val originLon: Double,
        val originLat: Double,
        val unionArea: Double,
    ) {
        var outer: List<Double>? = null
        val holes = mutableListOf<List<Double>>()

        fun toGeo(flat: List<Double>): List<GeoPointInterface> =
            flat.chunked(2).map { (x, y) ->
                GeoPoint.fromLatLong(latitude = originLat + y * unit, longitude = originLon + x * unit)
            }

        /** Planar area in grid units². */
        fun areaInUnits(ring: List<GeoPointInterface>): Double {
            var a = 0.0
            for (i in ring.indices) {
                val p = ring[i]
                val q = ring[(i + 1) % ring.size]
                val px = (p.longitude - originLon) / unit
                val py = (p.latitude - originLat) / unit
                val qx = (q.longitude - originLon) / unit
                val qy = (q.latitude - originLat) / unit
                a += px * qy - qx * py
            }
            return a / 2.0
        }
    }

    private fun readCases(): List<Case> {
        val stream =
            requireNotNull(javaClass.classLoader?.getResourceAsStream("polygon-hole-union-fixture.txt")) {
                "polygon-hole-union-fixture.txt not on the test classpath"
            }
        val cases = mutableListOf<Case>()
        stream.bufferedReader().forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine
            val parts = line.split("|")
            when (parts[0]) {
                "case" ->
                    cases.add(
                        Case(
                            name = parts[1],
                            unit = parts[2].toDouble(),
                            originLon = parts[3].toDouble(),
                            originLat = parts[4].toDouble(),
                            unionArea = parts[5].toDouble(),
                        ),
                    )
                "outer" -> cases.last().outer = parts[1].split(",").map { it.toDouble() }
                "hole" -> cases.last().holes.add(parts[1].split(",").map { it.toDouble() })
            }
        }
        return cases
    }

    @Test
    fun unionKeepsTheAreaOfTheUnion() {
        val cases = readCases()
        assertTrue("fixture has cases", cases.size >= 20)
        val failures = mutableListOf<String>()
        for (c in cases) {
            val holes = c.holes.map { c.toGeo(it) }
            val result =
                c.outer?.let { outer ->
                    val state = PolygonState(points = c.toGeo(outer), holes = holes)
                    val unioned = state.unionHoles()
                    assertSame("${c.name}: the outline is left alone", state.points, unioned.points)
                    unioned.holes
                } ?: unionHoleRings(holes)
            val area = result.sumOf { abs(c.areaInUnits(it)) }
            if (abs(area - c.unionArea) > 1e-6 * c.unionArea) {
                failures.add("${c.name}: area $area != union ${c.unionArea} (${result.size} rings from ${holes.size})")
            }
            if (result.any { c.areaInUnits(it) >= 0.0 }) {
                failures.add("${c.name}: a hole came back counter-clockwise")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
