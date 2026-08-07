package com.mapconductor.core.projection

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * Maximum extent of the Web Mercator projection: half the equatorial
 * circumference (πa ≈ 20037508.34 m). Projected x/y values fall within
 * ±this value.
 */
const val WEB_MERCATOR_MAX_EXTENT_METERS: Double = PI * Earth.RADIUS_METERS

object WebMercator : ProjectionInterface {
    override fun project(position: GeoPointInterface): Offset {
        val x = position.longitude * WEB_MERCATOR_MAX_EXTENT_METERS / 180
        val y = ln(tan((90 + position.latitude) * Math.PI / 360)) * WEB_MERCATOR_MAX_EXTENT_METERS / Math.PI
        return Offset(x.toFloat(), y.toFloat())
    }

    override fun unproject(point: Offset): GeoPointInterface {
        val longitude = point.x * 180 / WEB_MERCATOR_MAX_EXTENT_METERS
        val latitude = 180 / Math.PI * (2 * atan(exp(point.y * Math.PI / WEB_MERCATOR_MAX_EXTENT_METERS)) - Math.PI / 2)
        return object : GeoPointInterface {
            override val latitude: Double = latitude
            override val longitude: Double = longitude
            override val altitude: Double? = null

            override fun wrap(): GeoPointInterface = GeoPoint(latitude, longitude, altitude ?: 0.0).wrap()
        }
    }
}
