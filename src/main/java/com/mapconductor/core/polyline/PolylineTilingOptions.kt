package com.mapconductor.core.polyline

/**
 * Options for polyline tiling.
 *
 * When enabled and a map holds enough polylines, they are drawn into raster
 * tiles by the SDK's local tile server instead of being handed to the map SDK
 * one by one. Taps are still resolved against the original lines in
 * [PolylineManager], so tiling changes how polylines look, never what a tap
 * hits.
 *
 * The counterpart of [com.mapconductor.core.polygon.PolygonTilingOptions], and
 * the same options as js-sdk-core's and ios-sdk-core's `PolylineTilingOptions`.
 */
data class PolylineTilingOptions(
    val enabled: Boolean = true,
    /** Tile once a map holds at least this many polylines… */
    val minPolylineCount: Int = 2000,
    /** …or at least this many points across them, whichever comes first. */
    val minVertexCount: Int = 50_000,
) {
    /** The one place the threshold is decided; callers must not restate it. */
    fun shouldUseTiles(
        polylineCount: Int,
        vertexCount: Int,
    ): Boolean = enabled && (polylineCount >= minPolylineCount || vertexCount >= minVertexCount)

    companion object {
        val Default: PolylineTilingOptions = PolylineTilingOptions()
        val Disabled: PolylineTilingOptions = PolylineTilingOptions(enabled = false)
    }
}
