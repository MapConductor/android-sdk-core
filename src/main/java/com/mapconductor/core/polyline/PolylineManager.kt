package com.mapconductor.core.polyline

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.spherical.Planar
import com.mapconductor.core.spherical.WGS84Geodesic
import com.mapconductor.core.spherical.calculateMetersPerPixel
import com.mapconductor.settings.Settings
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import android.util.Log

data class PolylineHitResult<ActualPolyline>(
    val entity: PolylineEntityInterface<ActualPolyline>,
    val closestPoint: GeoPointInterface,
)

private data class DistanceResult(
    val distance: Double,
    val closestPoint: GeoPointInterface,
)

interface PolylineManagerInterface<ActualPolyline> {
    fun registerEntity(entity: PolylineEntityInterface<ActualPolyline>)

    fun removeEntity(id: String): PolylineEntityInterface<ActualPolyline>?

    fun getEntity(id: String): PolylineEntityInterface<ActualPolyline>?

    fun hasEntity(id: String): Boolean

    fun allEntities(): List<PolylineEntityInterface<ActualPolyline>>

    fun clear()

    fun find(
        position: GeoPointInterface,
        cameraPosition: MapCameraPosition? = null,
    ): PolylineHitResult<ActualPolyline>?
}

class PolylineManager<ActualPolyline> : PolylineManagerInterface<ActualPolyline> {
    companion object {
        private const val DEBUG_FIND = false
        private const val TAG = "PolylineManagerInterface"

        /** Meters per degree of latitude, rounded up: the box check may only ever let more through. */
        private const val METERS_PER_DEGREE = 111_000.0

        private fun d(msg: String) {
            if (DEBUG_FIND) Log.d(TAG, msg)
        }
    }

    private val entities = ConcurrentHashMap<String, PolylineEntityInterface<ActualPolyline>>()

    /**
     * Each line's bounding box, made when it is registered (every change of a
     * state registers it again). A tap first skips the lines whose box, grown
     * by the tolerance, it is not in: a city of roads is 50,000 lines and
     * 230,000 points, too many to measure on every tap. Null for a line the
     * box would not hold (geodesic, or half the world wide); those are always
     * measured.
     */
    private val boxes = ConcurrentHashMap<String, LineBox>()

    @InternalMapConductorApi
    private class LineBox(
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double,
    )

    override fun registerEntity(entity: PolylineEntityInterface<ActualPolyline>) {
        entities[entity.state.id] = entity
        val box = boxOf(entity.state)
        if (box != null) boxes[entity.state.id] = box else boxes.remove(entity.state.id)
    }

    override fun removeEntity(id: String): PolylineEntityInterface<ActualPolyline>? {
        boxes.remove(id)
        return entities.remove(id)
    }

    override fun getEntity(id: String): PolylineEntityInterface<ActualPolyline>? = entities[id]

    override fun hasEntity(id: String): Boolean = entities.containsKey(id)

    // ArrayList(values) avoids the size==1 race in Kotlin's toList() on concurrent maps.
    override fun allEntities(): List<PolylineEntityInterface<ActualPolyline>> = ArrayList(entities.values)

    override fun clear() {
        entities.clear()
        boxes.clear()
    }

    /**
     * The line nearest [position] that it is on, with the point on the line
     * nearest it (the contract on all three platforms).
     *
     * A tap is on a line when it is within the finger's size
     * ([Settings.tapTolerance]) of the line's edge: of its centre line plus
     * half its [PolylineState.strokeWidth], both on screen at the camera's
     * zoom. The same whether the line is drawn natively or in tiles.
     */
    override fun find(
        position: GeoPointInterface,
        cameraPosition: MapCameraPosition?,
    ): PolylineHitResult<ActualPolyline>? {
        val visibleRegion = cameraPosition?.visibleRegion?.bounds
        val candidates = mutableListOf<Triple<PolylineEntityInterface<ActualPolyline>, GeoPointInterface, Double>>()
        val fingerSize = ResourceProvider.dpToPx(Settings.Default.tapTolerance)
        val zoom = cameraPosition?.zoom ?: 0.0
        val metersPerPixel = calculateMetersPerPixel(position.latitude, zoom)
        val lngScale = cos(Math.toRadians(position.latitude)).coerceAtLeast(0.01)
        // dp → px as ResourceProvider.dpToPx does, without asking for every line.
        val density = ResourceProvider.dpToPx(1.0)

        entities.values.forEach { entity ->
            // clickable=false のポリラインはタップに対して透過する（CircleManager と同じ方針）。
            if (!entity.state.clickable) return@forEach
            val threshold = metersPerPixel * (fingerSize + entity.state.strokeWidth.value * density / 2.0)
            boxes[entity.state.id]?.let { box ->
                val dLat = threshold / METERS_PER_DEGREE
                val dLng = dLat / lngScale
                if (position.latitude < box.minLat - dLat || position.latitude > box.maxLat + dLat) return@forEach
                if (position.longitude < box.minLng - dLng || position.longitude > box.maxLng + dLng) return@forEach
            }
            // 補間せず、元の線分を直接使う
            for (i in 0 until entity.state.points.size - 1) {
                val box = GeoRectBounds()
                box.extend(entity.state.points[i])
                box.extend(entity.state.points[i + 1])

                if (visibleRegion == null || visibleRegion.intersects(box)) {
                    when (entity.state.geodesic) {
                        true ->
                            WGS84Geodesic.pointOnLineOrNull(
                                entity.state.points[i],
                                entity.state.points[i + 1],
                                position,
                                threshold,
                            )
                        false ->
                            Planar.pointOnLineOrNull(
                                entity.state.points[i],
                                entity.state.points[i + 1],
                                position,
                                threshold,
                            )
                    }?.let {
                        candidates.add(Triple(entity, it.first, it.second))
                    }
                }
            }
        }

        val closest = candidates.minByOrNull { it.third }
        return closest?.let { (entity, closestPoint, distance) ->
            d("hit ${entity.state.id} at $distance m")
            PolylineHitResult(entity = entity, closestPoint = closestPoint)
        }
    }

    private fun boxOf(state: PolylineState): LineBox? {
        if (state.geodesic || state.points.isEmpty()) return null
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLng = Double.POSITIVE_INFINITY
        var maxLng = Double.NEGATIVE_INFINITY
        for (p in state.points) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLng) minLng = p.longitude
            if (p.longitude > maxLng) maxLng = p.longitude
        }
        // Across the antimeridian the box would be the rest of the world.
        if (maxLng - minLng >= 180) return null
        return LineBox(minLat, maxLat, minLng, maxLng)
    }
}
