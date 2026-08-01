package com.mapconductor.core.polygon

/**
 * Unions overlapping hole rings (2D lon/lat plane) and returns a new [PolygonState] with merged holes.
 *
 * Implemented with the self-contained planar union in [unionHoleRings]
 * (ported from react-sdk; ios-sdk has the equivalent) — no external geometry library.
 *
 * Notes:
 * - This uses planar geometry (not geodesic). For very large polygons or near poles, results may differ from
 *   spherical expectations.
 * - If union fails for any reason, this returns the original [PolygonState] unchanged.
 */
fun PolygonState.unionHoles(): PolygonState {
    if (holes.size <= 1) return this

    val mergedHoles = unionHoleRings(holes)
    if (mergedHoles === holes) return this

    return PolygonState(
        points = points,
        holes = mergedHoles,
        id = id,
        strokeColor = strokeColor,
        strokeWidth = strokeWidth,
        fillColor = fillColor,
        geodesic = geodesic,
        zIndex = zIndex,
        extra = extra,
        onClick = onClick,
    )
}

/**
 * Alias for [unionHoles], to keep callsites short.
 */
fun PolygonState.union(): PolygonState = unionHoles()

/**
 * In-place variant: mutates [holes] to the merged result.
 */
fun PolygonState.unionHolesInPlace(): PolygonState {
    val merged = unionHoles()
    if (merged === this) return this
    holes = merged.holes
    return this
}
