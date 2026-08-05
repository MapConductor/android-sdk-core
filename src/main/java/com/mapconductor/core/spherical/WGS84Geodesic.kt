package com.mapconductor.core.spherical

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.projection.Earth
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 ellipsoid geodesic distance/interpolation via Vincenty's formulae.
 * Ported from the react-sdk/ios-sdk implementations so all platforms agree
 * without depending on an external geographiclib library. Vincenty can fail
 * to converge for near-antipodal point pairs; in that (rare, map-rendering-
 * irrelevant) case this falls back to a spherical (haversine) approximation,
 * matching react/ios behavior exactly.
 */
object WGS84Geodesic {
    private const val FLATTENING = Earth.FLATTENING
    private const val SEMI_MAJOR_AXIS = Earth.RADIUS_METERS
    private const val SEMI_MINOR_AXIS = Earth.SEMI_MINOR_AXIS_METERS

    // Authalic-sphere constants for ellipsoidal polygon area (see computeSignedArea).
    private val ECCENTRICITY_SQ = FLATTENING * (2 - FLATTENING)
    private val ECCENTRICITY = sqrt(ECCENTRICITY_SQ)
    private val AUTHALIC_QP =
        1 - ((1 - ECCENTRICITY_SQ) / (2 * ECCENTRICITY)) *
            ln((1 - ECCENTRICITY) / (1 + ECCENTRICITY))
    private val AUTHALIC_RADIUS = SEMI_MAJOR_AXIS * sqrt(AUTHALIC_QP / 2)

    private data class InverseResult(
        val distanceMeters: Double,
        val initialBearingRad: Double,
    )

    fun computeDistanceBetween(
        from: GeoPointInterface,
        to: GeoPointInterface,
    ): Double = inverse(from, to).distanceMeters

    fun computeHeading(
        from: GeoPointInterface,
        to: GeoPointInterface,
    ): Double = Math.toDegrees(inverse(from, to).initialBearingRad)

    fun computeOffset(
        origin: GeoPointInterface,
        distance: Double,
        heading: Double,
    ): GeoPoint {
        val destination = direct(origin, Math.toRadians(heading), distance)
        return GeoPoint(destination.first, destination.second, origin.altitude ?: 0.0)
    }

    fun computeOffsetOrigin(
        to: GeoPointInterface,
        distance: Double,
        heading: Double,
    ): GeoPoint? = computeOffset(to, distance, (heading + 180.0) % 360.0)

    fun computeLength(path: List<GeoPointInterface>): Double {
        if (path.size < 2) return 0.0
        var length = 0.0
        for (i in 1 until path.size) {
            length += computeDistanceBetween(path[i - 1], path[i])
        }
        return length
    }

    /** Densify a path by inserting points along the geodesic. */
    fun createInterpolatePoints(
        points: List<GeoPointInterface>,
        maxSegmentLength: Double = 10000.0,
    ): List<GeoPointInterface> = densifyAlongGeodesic(points, maxSegmentLength)

    /** Closest point on the geodesic segment within [thresholdMeters], or null. */
    fun pointOnLineOrNull(
        from: GeoPointInterface,
        to: GeoPointInterface,
        position: GeoPointInterface,
        thresholdMeters: Double,
    ): Pair<GeoPointInterface, Double>? =
        geodesicPointOnLineOrNull(from, to, position, thresholdMeters)

    /**
     * Ellipsoidal (authalic-sphere) polygon area. Maps geodetic latitudes to
     * authalic latitudes on the equal-area sphere of radius [AUTHALIC_RADIUS]
     * (which reproduces the exact WGS84 surface area), then applies the
     * spherical-excess formula. Accounts for flattening — unlike the sphere-based
     * [Spherical.computeSignedArea] — matching the ellipsoidal area to well under
     * 0.01% for typical polygons. Edges are treated as authalic-sphere arcs; the
     * higher-order geodesic-edge terms of Karney's exact method are omitted.
     */
    fun computeSignedArea(path: List<GeoPointInterface>): Double {
        if (path.size < 3) return 0.0
        var area = 0.0
        val pointCount = path.size
        for (i in 0 until pointCount) {
            val j = (i + 1) % pointCount
            val sinXi1 = authalicSinLatitude(path[i].latitude)
            val sinXi2 = authalicSinLatitude(path[j].latitude)
            val deltaLng = Math.toRadians(path[j].longitude - path[i].longitude)
            area += deltaLng * (2 + sinXi1 + sinXi2)
        }
        return area * AUTHALIC_RADIUS * AUTHALIC_RADIUS / 2.0
    }

    fun computeArea(path: List<GeoPointInterface>): Double = abs(computeSignedArea(path))

    private fun authalicSinLatitude(latitudeDeg: Double): Double {
        val sinPhi = sin(Math.toRadians(latitudeDeg))
        val q =
            (1 - ECCENTRICITY_SQ) *
                (sinPhi / (1 - ECCENTRICITY_SQ * sinPhi * sinPhi) -
                    (1 / (2 * ECCENTRICITY)) *
                        ln((1 - ECCENTRICITY * sinPhi) / (1 + ECCENTRICITY * sinPhi)))
        return q / AUTHALIC_QP
    }

    fun interpolate(
        from: GeoPointInterface,
        to: GeoPointInterface,
        fraction: Double,
    ): GeoPoint {
        val line = inverse(from, to)
        val destination = direct(from, line.initialBearingRad, line.distanceMeters * fraction)

        val altitude =
            when {
                from.altitude != null && to.altitude != null ->
                    from.altitude!! + fraction * (to.altitude!! - from.altitude!!)
                from.altitude != null -> from.altitude!!
                to.altitude != null -> to.altitude!!
                else -> 0.0
            }

        return GeoPoint(destination.first, destination.second, altitude)
    }

    private fun inverse(
        from: GeoPointInterface,
        to: GeoPointInterface,
    ): InverseResult {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lon2 = Math.toRadians(to.longitude)
        val longitudeDifference = lon2 - lon1

        val u1 = atan((1 - FLATTENING) * Math.tan(lat1))
        val u2 = atan((1 - FLATTENING) * Math.tan(lat2))
        val sinU1 = sin(u1)
        val cosU1 = cos(u1)
        val sinU2 = sin(u2)
        val cosU2 = cos(u2)

        var lambda = longitudeDifference
        var lambdaPrev: Double
        var iterLimit = 100
        var cosSqAlpha = 0.0
        var sinSigma = 0.0
        var cos2SigmaM = 0.0
        var cosSigma = 0.0
        var sigma = 0.0

        do {
            val sinLambda = sin(lambda)
            val cosLambda = cos(lambda)
            sinSigma =
                sqrt(
                    (cosU2 * sinLambda) * (cosU2 * sinLambda) +
                        (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda) *
                        (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda),
                )

            if (sinSigma == 0.0) {
                return InverseResult(0.0, 0.0)
            }

            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
            sigma = atan2(sinSigma, cosSigma)
            val sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma
            cosSqAlpha = 1 - sinAlpha * sinAlpha
            cos2SigmaM = cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha
            if (!cos2SigmaM.isFinite()) cos2SigmaM = 0.0

            val correctionFactor = FLATTENING / 16 * cosSqAlpha * (4 + FLATTENING * (4 - 3 * cosSqAlpha))
            lambdaPrev = lambda
            lambda =
                longitudeDifference +
                (1 - correctionFactor) * FLATTENING * sinAlpha *
                (
                    sigma +
                        correctionFactor * sinSigma *
                        (cos2SigmaM + correctionFactor * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM))
                )
            iterLimit -= 1
        } while (abs(lambda - lambdaPrev) > 1e-12 && iterLimit > 0)

        if (iterLimit == 0) return sphericalFallbackInverse(from, to)

        val uSq =
            cosSqAlpha * (SEMI_MAJOR_AXIS * SEMI_MAJOR_AXIS - SEMI_MINOR_AXIS * SEMI_MINOR_AXIS) /
            (SEMI_MINOR_AXIS * SEMI_MINOR_AXIS)
        val ellipsoidFactor = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
        val correctionTerm = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
        val deltaSigma =
            correctionTerm * sinSigma * (
                cos2SigmaM + correctionTerm / 4 * (
                    cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
                        correctionTerm / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) *
                        (-3 + 4 * cos2SigmaM * cos2SigmaM)
                )
            )

        val distance = SEMI_MINOR_AXIS * ellipsoidFactor * (sigma - deltaSigma)
        val initialBearing =
            atan2(
                cosU2 * sin(lambda),
                cosU1 * sinU2 - sinU1 * cosU2 * cos(lambda),
            )

        return InverseResult(distance, initialBearing)
    }

    private fun direct(
        origin: GeoPointInterface,
        initialBearingRad: Double,
        distanceMeters: Double,
    ): Pair<Double, Double> {
        val lat1 = Math.toRadians(origin.latitude)
        val lon1 = Math.toRadians(origin.longitude)
        val sinAlpha1 = sin(initialBearingRad)
        val cosAlpha1 = cos(initialBearingRad)

        val tanU1 = (1 - FLATTENING) * Math.tan(lat1)
        val cosU1 = 1 / sqrt(1 + tanU1 * tanU1)
        val sinU1 = tanU1 * cosU1
        val sigma1 = atan2(tanU1, cosAlpha1)
        val sinAlpha = cosU1 * sinAlpha1
        val cosSqAlpha = 1 - sinAlpha * sinAlpha
        val uSq =
            cosSqAlpha * (SEMI_MAJOR_AXIS * SEMI_MAJOR_AXIS - SEMI_MINOR_AXIS * SEMI_MINOR_AXIS) /
            (SEMI_MINOR_AXIS * SEMI_MINOR_AXIS)
        val ellipsoidFactor = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
        val correctionTerm = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))

        var sigma = distanceMeters / (SEMI_MINOR_AXIS * ellipsoidFactor)
        var sigmaPrev: Double
        var cos2SigmaM = 0.0
        var sinSigma = 0.0
        var cosSigma = 0.0
        // ios-sdk と同じく非収束の病的入力での無限ループを防ぐ反復上限。
        var iterLimit = 1000

        do {
            cos2SigmaM = cos(2 * sigma1 + sigma)
            sinSigma = sin(sigma)
            cosSigma = cos(sigma)
            val deltaSigma =
                correctionTerm * sinSigma * (
                    cos2SigmaM + correctionTerm / 4 * (
                        cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
                            correctionTerm / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) *
                            (-3 + 4 * cos2SigmaM * cos2SigmaM)
                    )
                )
            sigmaPrev = sigma
            sigma = distanceMeters / (SEMI_MINOR_AXIS * ellipsoidFactor) + deltaSigma
            iterLimit--
        } while (abs(sigma - sigmaPrev) > 1e-12 && iterLimit > 0)

        val tmp = sinU1 * sinSigma - cosU1 * cosSigma * cosAlpha1
        val lat2 =
            atan2(
                sinU1 * cosSigma + cosU1 * sinSigma * cosAlpha1,
                (1 - FLATTENING) * sqrt(sinAlpha * sinAlpha + tmp * tmp),
            )
        val lambda =
            atan2(
                sinSigma * sinAlpha1,
                cosU1 * cosSigma - sinU1 * sinSigma * cosAlpha1,
            )
        val correctionFactor = FLATTENING / 16 * cosSqAlpha * (4 + FLATTENING * (4 - 3 * cosSqAlpha))
        val longitudeDifference =
            lambda - (1 - correctionFactor) * FLATTENING * sinAlpha *
            (
                sigma +
                    correctionFactor * sinSigma *
                    (cos2SigmaM + correctionFactor * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM))
            )
        val lon2 = lon1 + longitudeDifference

        return Pair(Math.toDegrees(lat2), normalizeLng(Math.toDegrees(lon2)))
    }

    private fun sphericalFallbackInverse(
        from: GeoPointInterface,
        to: GeoPointInterface,
    ): InverseResult {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLat = Math.toRadians(to.latitude - from.latitude)
        val deltaLng = Math.toRadians(to.longitude - from.longitude)
        val a =
            sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLng / 2) * sin(deltaLng / 2)
        val centralAngle = 2 * atan2(sqrt(a), sqrt(1 - a))
        val y = sin(deltaLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLng)
        return InverseResult(
            distanceMeters = SEMI_MAJOR_AXIS * centralAngle,
            initialBearingRad = atan2(y, x),
        )
    }

    private fun normalizeLng(lng: Double): Double = ((((lng + 180.0) % 360.0) + 360.0) % 360.0) - 180.0
}
