package com.mapconductor.core.projection

import com.mapconductor.core.features.GeoPointInterface

interface ProjectionInterface {
    fun project(position: GeoPointInterface): ProjectedPoint

    fun unproject(point: ProjectedPoint): GeoPointInterface
}
