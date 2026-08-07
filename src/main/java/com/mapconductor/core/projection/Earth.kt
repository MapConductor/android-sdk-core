package com.mapconductor.core.projection

import kotlin.math.PI

/**
 * WGS84 ellipsoid parameters. https://epsg.org/ellipsoid_7030/WGS-84.html
 */
object Earth {
    /** WGS84 semi-major axis (equatorial radius) in meters. */
    const val RADIUS_METERS: Double = 6378137.0

    /** WGS84 flattening f = 1 / 298.257223563. */
    const val FLATTENING: Double = 1.0 / 298.257223563

    /** Equatorial circumference (2πa) in meters. */
    const val CIRCUMFERENCE_METERS: Double = 2.0 * PI * RADIUS_METERS

    /** WGS84 semi-minor axis (polar radius) b = a(1 - f) in meters. */
    const val SEMI_MINOR_AXIS_METERS: Double = RADIUS_METERS * (1.0 - FLATTENING)

    /** WGS84 first eccentricity squared e² = f(2 - f). */
    const val ECCENTRICITY_SQUARED: Double = FLATTENING * (2.0 - FLATTENING)
}
