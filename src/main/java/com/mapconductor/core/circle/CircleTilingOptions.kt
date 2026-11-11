package com.mapconductor.core.circle

/**
 * Options for circle tiling.
 *
 * When enabled and a map holds enough circles, they are drawn into raster
 * tiles by the SDK's local tile server instead of being handed to the map SDK
 * one by one. Taps are still resolved against the original circles in
 * [CircleManager], so tiling changes how circles look, never what a tap hits.
 *
 * The counterpart of [com.mapconductor.core.polygon.PolygonTilingOptions] and
 * [com.mapconductor.core.polyline.PolylineTilingOptions], and the same options
 * as js-sdk-core's and ios-sdk-core's `CircleTilingOptions`. A circle has no
 * vertices to count, so the threshold is the number of circles alone.
 */
data class CircleTilingOptions(
    val enabled: Boolean = true,
    /** Tile once a map holds at least this many circles. */
    val minCircleCount: Int = 2001,
) {
    /** The one place the threshold is decided; callers must not restate it. */
    fun shouldUseTiles(circleCount: Int): Boolean = enabled && circleCount >= minCircleCount

    companion object {
        val Default: CircleTilingOptions = CircleTilingOptions()
        val Disabled: CircleTilingOptions = CircleTilingOptions(enabled = false)
    }
}
