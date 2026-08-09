package com.mapconductor.core.groundimage

import com.mapconductor.core.features.GeoPointInterface
import java.util.concurrent.ConcurrentHashMap

interface GroundImageManagerInterface<ActualGroundImage> {
    fun registerEntity(entity: GroundImageEntityInterface<ActualGroundImage>)

    fun removeEntity(id: String): GroundImageEntityInterface<ActualGroundImage>?

    fun getEntity(id: String): GroundImageEntityInterface<ActualGroundImage>?

    fun hasEntity(id: String): Boolean

    fun allEntities(): List<GroundImageEntityInterface<ActualGroundImage>>

    fun clear()

    fun find(position: GeoPointInterface): GroundImageEntityInterface<ActualGroundImage>?
}

class GroundImageManager<ActualGroundImage> : GroundImageManagerInterface<ActualGroundImage> {
    private val entities = ConcurrentHashMap<String, GroundImageEntityInterface<ActualGroundImage>>()

    override fun registerEntity(entity: GroundImageEntityInterface<ActualGroundImage>) {
        entities[entity.state.id] = entity
    }

    override fun removeEntity(id: String): GroundImageEntityInterface<ActualGroundImage>? = entities.remove(id)

    override fun getEntity(id: String): GroundImageEntityInterface<ActualGroundImage>? = entities[id]

    override fun hasEntity(id: String): Boolean = entities.containsKey(id)

    // ArrayList(values) avoids the size==1 race in Kotlin's toList() on concurrent maps.
    override fun allEntities(): List<GroundImageEntityInterface<ActualGroundImage>> = ArrayList(entities.values)

    override fun clear() {
        entities.clear()
    }

    override fun find(position: GeoPointInterface): GroundImageEntityInterface<ActualGroundImage>? =
        entities.values.firstOrNull { entity ->
            // clickable=false のグラウンドイメージはタップに対して透過する（CircleManager と同じ方針）。
            entity.state.clickable && entity.state.bounds.contains(position)
        }
}
