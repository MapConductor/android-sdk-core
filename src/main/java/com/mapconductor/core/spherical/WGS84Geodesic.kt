package com.mapconductor.core.spherical

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface

/**
 * Thin compatibility layer.
 *
 * The implementations that used to live here duplicated other modules: the
 * Vincenty inverse solution duplicated [GeographicLibCalculator] (which
 * additionally provides a spherical fallback when the iteration fails to
 * converge, where the old copy returned 0), and [computeHeading] /
 * [interpolate] duplicated the spherical formulas in [Spherical]. The public
 * API is preserved by delegating.
 */
object WGS84Geodesic {
    /**
     * WGS84 ellipsoid distance (Vincenty), compatible with Google Maps
     * geodesic calculations.
     */
    fun computeDistanceBetween(
        from: GeoPointInterface,
        to: GeoPointInterface,
    ): Double = GeographicLibCalculator.computeDistanceBetween(from, to)

    /**
     * Heading from one point to another, in degrees clockwise from North
     * within the range (-180, 180].
     */
    fun computeHeading(
        from: GeoPointInterface,
        to: GeoPointInterface,
    ): Double = Spherical.computeHeading(from, to)

    /**
     * Spherical linear interpolation (Slerp) between two points.
     */
    fun interpolate(
        from: GeoPointInterface,
        to: GeoPointInterface,
        fraction: Double,
    ): GeoPoint = Spherical.sphericalInterpolate(from, to, fraction)
}
