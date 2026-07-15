package com.mapconductor.core.raster

import com.mapconductor.core.features.GeoPointInterface
import java.util.concurrent.ConcurrentHashMap

interface RasterLayerManagerInterface<ActualLayer> {
    fun registerEntity(entity: RasterLayerEntityInterface<ActualLayer>)

    fun removeEntity(id: String): RasterLayerEntityInterface<ActualLayer>?

    fun getEntity(id: String): RasterLayerEntityInterface<ActualLayer>?

    fun hasEntity(id: String): Boolean

    fun allEntities(): List<RasterLayerEntityInterface<ActualLayer>>

    fun clear()

    fun find(position: GeoPointInterface): RasterLayerEntityInterface<ActualLayer>?
}

class RasterLayerManager<ActualLayer> : RasterLayerManagerInterface<ActualLayer> {
    private val entities = ConcurrentHashMap<String, RasterLayerEntityInterface<ActualLayer>>()

    override fun registerEntity(entity: RasterLayerEntityInterface<ActualLayer>) {
        entities[entity.state.id] = entity
    }

    override fun removeEntity(id: String): RasterLayerEntityInterface<ActualLayer>? = entities.remove(id)

    override fun getEntity(id: String): RasterLayerEntityInterface<ActualLayer>? = entities[id]

    override fun hasEntity(id: String): Boolean = entities.containsKey(id)

    // ArrayList(values) copies via toArray(); Kotlin's toList() has a size==1 fast path that
    // calls iterator().next() and throws NoSuchElementException when another thread removes
    // the last entry between the size read and the iteration.
    override fun allEntities(): List<RasterLayerEntityInterface<ActualLayer>> = ArrayList(entities.values)

    override fun clear() {
        entities.clear()
    }

    override fun find(position: GeoPointInterface): RasterLayerEntityInterface<ActualLayer>? = null
}
