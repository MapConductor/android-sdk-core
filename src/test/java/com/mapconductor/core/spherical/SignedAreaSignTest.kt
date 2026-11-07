package com.mapconductor.core.spherical

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * computeSignedArea の符号の約束: 北が上の地図で時計回りのリングが正、反時計回りが負。
 * js-sdk-core の `SignedAreaSign.test.mjs`、ios-sdk の `SignedAreaSignTests` と同じ観点。
 */
class SignedAreaSignTest {
    private val sw = GeoPoint(35.0, 139.0)
    private val se = GeoPoint(35.0, 140.0)
    private val ne = GeoPoint(36.0, 140.0)
    private val nw = GeoPoint(36.0, 139.0)
    private val clockwise = listOf(sw, nw, ne, se)
    private val counterClockwise = listOf(sw, se, ne, nw)

    private fun check(signedArea: (List<GeoPointInterface>) -> Double) {
        val cw = signedArea(clockwise)
        val ccw = signedArea(counterClockwise)
        assertTrue("clockwise should be positive: $cw", cw > 0.0)
        assertTrue("counter-clockwise should be negative: $ccw", ccw < 0.0)
        assertEquals(cw, -ccw, 1e-3)
    }

    @Test
    fun spherical_clockwiseIsPositive() = check(Spherical::computeSignedArea)

    @Test
    fun wgs84Geodesic_clockwiseIsPositive() = check(WGS84Geodesic::computeSignedArea)
}
