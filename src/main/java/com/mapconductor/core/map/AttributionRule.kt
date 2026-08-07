package com.mapconductor.core.map

import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import java.io.Serializable
import kotlin.math.floor

data class AttributionRule(
    val attribution: String,
    val minZoom: Int? = null,
    val maxZoom: Int? = null,
    val bounds: GeoRectBounds? = null,
) : Serializable

fun resolveAttributionRules(
    rules: List<AttributionRule>,
    camera: MapCameraPositionInterface,
): List<String> {
    val tileZoom = floor(camera.zoom).toInt()
    val visibleBounds = camera.visibleRegion?.bounds
    return rules
        .asSequence()
        .filter { rule -> rule.minZoom == null || tileZoom >= rule.minZoom }
        .filter { rule -> rule.maxZoom == null || tileZoom <= rule.maxZoom }
        .filter { rule ->
            rule.bounds == null ||
                if (visibleBounds != null) {
                    rule.bounds.intersects(visibleBounds)
                } else {
                    rule.bounds.contains(camera.position)
                }
        }.map { it.attribution.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}

fun resolveMapAttributions(
    designRules: List<AttributionRule>,
    rasterLayers: Collection<RasterLayerState>,
    camera: MapCameraPositionInterface,
): List<String> {
    val tileZoom = floor(camera.zoom).toInt()
    val rasterRules =
        rasterLayers
            .asSequence()
            .filter { it.visible }
            .mapNotNull { it.source as? RasterLayerSource.UrlTemplate }
            .filter { source -> source.minZoom == null || tileZoom >= source.minZoom }
            .filter { source -> source.maxZoom == null || tileZoom <= source.maxZoom }
            .flatMap { it.attributionRules.asSequence() }
            .toList()
    return (resolveAttributionRules(designRules, camera) + resolveAttributionRules(rasterRules, camera))
        .distinct()
}
