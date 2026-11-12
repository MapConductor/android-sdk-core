package com.mapconductor.core.projection

/**
 * 地図エンジンが地図を並べている座標系。**画面座標ではない。**
 *
 * [com.mapconductor.core.map.MapViewHolderInterface.toProjectedPoint] が返す
 * [ProjectedPoint] がどの座標系の値かを示す。
 *
 * - [code] は `"EPSG:3857"` のような識別子。エンジンが独自に定義した投影で、
 *   EPSG などの番号を持たないときは null。
 * - [unit] は x / y の単位。
 *
 * Mercator しか持たないエンジンと、地球儀表示中のエンジンは [WebMercator]
 * （EPSG:3857 メートル）になる。
 */
data class MapCoordinateSystem(
    val code: String?,
    val unit: ProjectedUnit,
) {
    companion object {
        /** EPSG:3857。x / y はメートル。 */
        val WebMercator = MapCoordinateSystem("EPSG:3857", ProjectedUnit.Meters)

        /** EPSG:4326。x が経度、y が緯度で、単位は度。 */
        val WGS84 = MapCoordinateSystem("EPSG:4326", ProjectedUnit.Degrees)
    }
}

/** [ProjectedPoint] の x / y の単位。 */
enum class ProjectedUnit {
    Meters,
    Degrees,

    /** フィートなど上の 2 つ以外、またはエンジン独自の単位。 */
    Other,
}
