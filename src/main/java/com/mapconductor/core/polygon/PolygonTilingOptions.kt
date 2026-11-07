package com.mapconductor.core.polygon

/**
 * Options for polygon tiling.
 *
 * When enabled and a map holds enough polygons, they are drawn into raster
 * tiles by the SDK's local tile server instead of being handed to the map SDK
 * one by one. Taps are still resolved against the original shapes in
 * [PolygonManager], so tiling changes how polygons look, never what a tap hits.
 *
 * The counterpart of [com.mapconductor.core.marker.MarkerTilingOptions], and
 * the same options as js-sdk-core's and ios-sdk-core's `PolygonTilingOptions`.
 */
data class PolygonTilingOptions(
    val enabled: Boolean = true,
    /** Tile once a map holds at least this many polygons… */
    val minPolygonCount: Int = 2000,
    /** …or at least this many vertices across them, whichever comes first. */
    val minVertexCount: Int = 50_000,
) {
    /** The one place the threshold is decided; callers must not restate it. */
    fun shouldUseTiles(
        polygonCount: Int,
        vertexCount: Int,
    ): Boolean = enabled && (polygonCount >= minPolygonCount || vertexCount >= minVertexCount)

    companion object {
        val Default: PolygonTilingOptions = PolygonTilingOptions()
        val Disabled: PolygonTilingOptions = PolygonTilingOptions(enabled = false)
    }
}
