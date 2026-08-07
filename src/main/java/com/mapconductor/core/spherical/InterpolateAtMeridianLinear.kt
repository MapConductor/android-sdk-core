package com.mapconductor.core.spherical

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface

/**
 * Performs linear interpolation to find the meridian crossing point.
 */
fun interpolateAtMeridianLinear(
    from: GeoPointInterface,
    to: GeoPointInterface,
): GeoPoint {
    val fromLng = from.longitude
    val toLng = to.longitude

    // Determine which meridian to interpolate to (180 or -180)
    val targetMeridian = if (fromLng >= 0) 180.0 else -180.0

    // Calculate the fraction where meridian crossing occurs.
    // The raw difference exceeds 180° for an antimeridian-crossing segment (the only
    // case this function is called for), so unwrap it to the short-way signed span;
    // otherwise the fraction comes out negative and the latitude is extrapolated
    // in the wrong direction.
    val directDiff = toLng - fromLng
    val totalLngDiff =
        when {
            directDiff > 180.0 -> directDiff - 360.0
            directDiff < -180.0 -> directDiff + 360.0
            else -> directDiff
        }
    val meridianDiff = targetMeridian - fromLng
    val fraction =
        if (totalLngDiff == 0.0) 0.0 else (meridianDiff / totalLngDiff).coerceIn(0.0, 1.0)

    // Interpolate latitude and altitude at the meridian
    val interpolatedLatitude = from.latitude + fraction * (to.latitude - from.latitude)
    val interpolatedAltitude =
        when {
            from.altitude != null && to.altitude != null ->
                from.altitude!! + fraction * (to.altitude!! - from.altitude!!)
            from.altitude != null -> from.altitude
            to.altitude != null -> to.altitude
            else -> 0.0
        }

    return GeoPoint(
        latitude = interpolatedLatitude,
        longitude = targetMeridian,
        altitude = interpolatedAltitude!!,
    )
}
