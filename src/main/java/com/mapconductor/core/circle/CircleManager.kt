package com.mapconductor.core.circle

import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.spherical.Spherical
import com.mapconductor.core.spherical.calculateMetersPerPixel
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

interface CircleManagerInterface<ActualCircle> {
    fun registerEntity(entity: CircleEntityInterface<ActualCircle>)

    fun removeEntity(id: String): CircleEntityInterface<ActualCircle>?

    fun getEntity(id: String): CircleEntityInterface<ActualCircle>?

    fun hasEntity(id: String): Boolean

    fun allEntities(): List<CircleEntityInterface<ActualCircle>>

    fun nativeEntities(): List<CircleEntityInterface<ActualCircle>> = allEntities().filter { it.circle != null }

    fun setTiledSource(source: CircleTiler?): Boolean = false

    fun clear()

    fun find(position: GeoPointInterface): CircleEntityInterface<ActualCircle>?

    /**
     * The circle a tap at [position] is on, with [cameraPosition]'s zoom to
     * measure half the stroke past the edge. Without a camera, or for a
     * manager that does not measure the stroke, the same as [find].
     */
    fun find(
        position: GeoPointInterface,
        cameraPosition: MapCameraPosition?,
    ): CircleEntityInterface<ActualCircle>? = find(position)
}

class CircleManager<ActualCircle> : CircleManagerInterface<ActualCircle> {
    private val entities: ConcurrentHashMap<String, CircleEntityInterface<ActualCircle>> = ConcurrentHashMap()
    @Volatile private var tiledSource: CircleTiler? = null

    override fun setTiledSource(source: CircleTiler?): Boolean {
        tiledSource = source
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun tiledEntity(state: CircleState): CircleEntityInterface<ActualCircle> = CircleEntity(null as ActualCircle, state)

    override fun getEntity(id: String): CircleEntityInterface<ActualCircle>? = entities[id] ?: tiledSource?.state(id)?.let(::tiledEntity)

    override fun hasEntity(id: String): Boolean = entities.containsKey(id) || tiledSource?.isTiled(id) == true

    override fun removeEntity(id: String): CircleEntityInterface<ActualCircle>? {
        val removed = entities.remove(id)
        if (removed != null) return removed
        val source = tiledSource ?: return null
        val state = source.state(id) ?: return null
        source.remove(id)
        return tiledEntity(state)
    }

    override fun registerEntity(entity: CircleEntityInterface<ActualCircle>) {
        if (entity.circle == null && tiledSource?.isTiled(entity.state.id) == true) {
            entities.remove(entity.state.id)
            return
        }
        entities[entity.state.id] = entity
    }

    fun updateEntity(entity: CircleEntityInterface<ActualCircle>) {
        registerEntity(entity)
    }

    // ArrayList(values) avoids the size==1 race in Kotlin's toList() on concurrent maps.
    override fun nativeEntities(): List<CircleEntityInterface<ActualCircle>> = ArrayList(entities.values)

    override fun allEntities(): List<CircleEntityInterface<ActualCircle>> = nativeEntities().toMutableList().also { result ->
        tiledSource?.visitStates { state -> if (!entities.containsKey(state.id)) result.add(tiledEntity(state)) }
    }

    override fun clear() {
        entities.clear()
        tiledSource = null
    }

    override fun find(position: GeoPointInterface): CircleEntityInterface<ActualCircle>? = find(position, null)

    /**
     * The circle a tap at [position] is on (the same on all three platforms,
     * and whether the circle is drawn natively or in tiles):
     *
     * - on it when the tap is within its radius plus half its stroke past the
     *   edge, the stroke measured on screen at [cameraPosition]'s zoom (no
     *   camera: the radius alone). The finger's size is not added: a circle is
     *   an area, as a polygon is, and among a street of small trees the finger
     *   would pick up the neighbours.
     * - clickable = false circles let the tap through.
     * - of several, the one on top: the highest zIndex (an unset one is the
     *   automatic one from the centre), then the nearest centre.
     */
    override fun find(
        position: GeoPointInterface,
        cameraPosition: MapCameraPosition?,
    ): CircleEntityInterface<ActualCircle>? {
        val metersPerPixel = cameraPosition?.let { calculateMetersPerPixel(position.latitude, it.zoom) } ?: 0.0
        // Meters per degree of latitude, rounded down: the latitude check may only ever let more through.
        val metersPerDegree = 110_000.0
        var best: CircleEntityInterface<ActualCircle>? = null
        var bestState: CircleState? = null
        var bestZ = Int.MIN_VALUE
        var bestDistance = Double.POSITIVE_INFINITY
        fun consider(state: CircleState, entity: CircleEntityInterface<ActualCircle>?) {
            if (!state.clickable) return
            val reach = state.radiusMeters + metersPerPixel * state.strokeWidth.value / 2.0
            if (!(reach >= 0.0)) return
            // A degree of latitude is never under metersPerDegree: one further
            // north or south than the reach cannot be on it. Skips the distance
            // for nearly every circle of a city.
            if (abs(state.center.latitude - position.latitude) > reach / metersPerDegree) return
            val distance = Spherical.computeDistanceBetween(state.center, position)
            if (distance > reach) return
            val z = effectiveZIndex(state)
            if (bestState == null || z > bestZ || (z == bestZ && distance < bestDistance)) {
                best = entity
                bestState = state
                bestZ = z
                bestDistance = distance
            }
        }
        for (entity in entities.values) consider(entity.state, entity)
        tiledSource?.visitStates { state -> if (!entities.containsKey(state.id)) consider(state, null) }
        return best ?: bestState?.let(::tiledEntity)
    }
}
