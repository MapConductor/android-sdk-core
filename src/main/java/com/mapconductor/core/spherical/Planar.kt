package com.mapconductor.core.spherical

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.normalizeLng

/**
 * The straight-line ("planar") line model: edges are straight lines in lat/lng
 * space (equirectangular), not great circles or geodesics. Mirrors the path-op
 * surface of the earth-model calculators so callers can pick a model by object —
 * e.g. `(if (geodesic) WGS84Geodesic else Planar).createInterpolatePoints(...)`.
 */
object Planar {
    /**
     * Straight lat/lng interpolation (handles antimeridian crossing).
     *
     * Treats coordinates as if they were on a flat plane, so results get inaccurate over large
     * distances, but it is computationally cheaper than the earth-model calculators. For longitude
     * it automatically takes the shorter path, which may cross the 180°/-180° meridian.
     *
     * Use this only when:
     * - Working with small distances where Earth's curvature is negligible
     * - Performance is critical and accuracy can be sacrificed
     * - Working with projected coordinate systems
     *
     * @param from Starting point
     * @param to Ending point
     * @param fraction Interpolation fraction (0.0 = from, 1.0 = to)
     * @return Linearly interpolated position
     */
    fun interpolate(
        from: GeoPointInterface,
        to: GeoPointInterface,
        fraction: Double,
    ): GeoPoint {
        val interpolatedAltitude =
            when {
                from.altitude != null && to.altitude != null ->
                    from.altitude!! + fraction * (to.altitude!! - from.altitude!!)
                from.altitude != null -> from.altitude
                to.altitude != null -> to.altitude
                else -> 0.0
            }

        val interpolatedLatitude = from.latitude + fraction * (to.latitude - from.latitude)

        val fromLng = from.longitude
        val toLng = to.longitude
        val directDiff = toLng - fromLng
        val crossMeridianDiff =
            when {
                directDiff > 180 -> directDiff - 360
                directDiff < -180 -> directDiff + 360
                else -> directDiff
            }
        val interpolatedLongitude = fromLng + fraction * crossMeridianDiff

        return GeoPoint(
            latitude = interpolatedLatitude,
            longitude = normalizeLng(interpolatedLongitude),
            altitude = interpolatedAltitude!!,
        )
    }

    /** Densify a path by inserting points along straight lat/lng lines. */
    fun createInterpolatePoints(
        points: List<GeoPointInterface>,
        maxSegmentLength: Double = 10000.0,
    ): List<GeoPointInterface> = densifyAlongStraightLine(points, maxSegmentLength)

    /** Closest point on the straight segment within [thresholdMeters], or null. */
    fun pointOnLineOrNull(
        from: GeoPointInterface,
        to: GeoPointInterface,
        position: GeoPointInterface,
        thresholdMeters: Double,
    ): Pair<GeoPointInterface, Double>? = linearPointOnLineOrNull(from, to, position, thresholdMeters)
}
