package com.mapconductor.core.spherical

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import kotlin.math.min

fun pointOnGeodesicSegmentOrNull(
    from: GeoPointInterface,
    to: GeoPointInterface,
    position: GeoPointInterface,
    thresholdMeters: Double,
): Pair<GeoPointInterface, Double>? {
    val totalDistance = GeographicLibCalculator.computeDistanceBetween(from, to)

    if (totalDistance == 0.0) {
        val distPosFrom = GeographicLibCalculator.computeDistanceBetween(from, position)
        return if (distPosFrom <= thresholdMeters) {
            Pair(GeoPoint(from.latitude, from.longitude, from.altitude ?: 0.0), distPosFrom)
        } else {
            null
        }
    }

    // 三分探索で最近点を見つける
    var left = 0.0
    var right = 1.0
    val epsilon = 1e-6 // 十分な精度

    while (right - left > epsilon) {
        val m1 = left + (right - left) / 3.0
        val m2 = right - (right - left) / 3.0

        val point1 = GeographicLibCalculator.interpolate(from, to, m1)
        val dist1 = GeographicLibCalculator.computeDistanceBetween(point1, position)

        val point2 = GeographicLibCalculator.interpolate(from, to, m2)
        val dist2 = GeographicLibCalculator.computeDistanceBetween(point2, position)

        if (dist1 > dist2) {
            left = m1
        } else {
            right = m2
        }
    }

    val bestFraction = (left + right) / 2.0

    // 線分外の判定
    if (bestFraction <= 0.0 || bestFraction >= 1.0) {
        val distFrom = GeographicLibCalculator.computeDistanceBetween(from, position)
        val distTo = GeographicLibCalculator.computeDistanceBetween(to, position)

        val actualMin = min(distFrom, distTo)
        if (actualMin > thresholdMeters) return null

        return Pair(
            if (distFrom <= distTo) {
                GeoPoint(from.latitude, from.longitude, from.altitude ?: to.altitude ?: 0.0)
            } else {
                GeoPoint(to.latitude, to.longitude, to.altitude ?: from.altitude ?: 0.0)
            },
            actualMin,
        )
    }

    val closestPoint = GeographicLibCalculator.interpolate(from, to, bestFraction)

    val minDistance = GeographicLibCalculator.computeDistanceBetween(closestPoint, position)

    if (minDistance > thresholdMeters) return null

    val altitude =
        when {
            from.altitude != null && to.altitude != null ->
                from.altitude!! + bestFraction * (to.altitude!! - from.altitude!!)
            from.altitude != null -> from.altitude!!
            to.altitude != null -> to.altitude!!
            else -> 0.0
        }

    val result = GeoPoint(closestPoint.latitude, closestPoint.longitude, altitude)
    return Pair(result, minDistance)
}
