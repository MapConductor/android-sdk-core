package com.mapconductor.core.map

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import kotlin.math.abs

data class VisibleRegion(
    val bounds: GeoRectBounds,
    val nearLeft: GeoPointInterface?,
    val nearRight: GeoPointInterface?,
    val farLeft: GeoPointInterface?,
    val farRight: GeoPointInterface?,
)

interface MapCameraPositionInterface {
    val position: GeoPointInterface
    val zoom: Double

    /**
     * 地図の回転角（度、0 が北）。
     *
     * 値を増やすと地図は**時計回り（右）**に回る。90 なら地図が右へ 90 度回り、
     * 画面の上には西が来る。多くのネイティブ SDK の「カメラの向き（heading）」とは
     * 符号が逆なので、変換は [CameraBearing] を通すこと。
     */
    val bearing: Double
    val tilt: Double
    val paddings: MapPaddingsInterface?
    val visibleRegion: VisibleRegion?
}

class MapCameraPosition(
    position: GeoPointInterface,
    zoom: Double? = null,
    bearing: Double? = null,
    tilt: Double? = null,
    override val paddings: MapPaddingsInterface? = MapPaddings.Companion.Zeros,
    override val visibleRegion: VisibleRegion? = null,
) : MapCameraPositionInterface {
    override val position: GeoPoint = GeoPoint.from(position)

    // 指定された値。null は未指定。
    private val givenZoom: Double? = zoom
    private val givenBearing: Double? = bearing
    private val givenTilt: Double? = tilt

    /** 指定されなかったときは 0。指定されたかどうかは [isZoomSpecified] で区別する。 */
    override val zoom: Double = zoom ?: 0.0

    /** 指定されなかったときは 0。指定されたかどうかは [isBearingSpecified] で区別する。 */
    override val bearing: Double = bearing ?: 0.0

    /** 指定されなかったときは 0。指定されたかどうかは [isTiltSpecified] で区別する。 */
    override val tilt: Double = tilt ?: 0.0

    /**
     * `zoom` / `bearing` / `tilt` が明示されたか。
     *
     * 明示された 0（zoom 0 = 世界全体、真北、真上から）と未指定を区別するために持つ。
     * [MapViewState.moveCameraTo] は未指定のプロパティだけを現在のカメラから
     * 引き継ぐ（[resolvedOnto]）。
     */
    val isZoomSpecified: Boolean get() = givenZoom != null
    val isBearingSpecified: Boolean get() = givenBearing != null
    val isTiltSpecified: Boolean get() = givenTilt != null

    /**
     * 未指定の `zoom` / `bearing` / `tilt` を [current] の値で埋めたカメラを返す。
     *
     * 明示されたプロパティは、0 であってもそのまま使う。
     */
    fun resolvedOnto(current: MapCameraPositionInterface): MapCameraPosition {
        if (isZoomSpecified && isBearingSpecified && isTiltSpecified) return this
        return MapCameraPosition(
            position = position,
            zoom = givenZoom ?: current.zoom,
            bearing = givenBearing ?: current.bearing,
            tilt = givenTilt ?: current.tilt,
            paddings = paddings,
            visibleRegion = visibleRegion,
        )
    }

    fun equals(other: MapCameraPositionInterface): Boolean =
        this.position.equals(other = other.position) &&
            this.zoomEquals(other) &&
            this.bearingEquals(other) &&
            this.tiltEquals(other)

    // 渡されなかったプロパティは、未指定だったならコピー後も未指定のまま。
    fun copy(
        position: GeoPointInterface? = this.position,
        zoom: Double? = this.givenZoom,
        bearing: Double? = this.givenBearing,
        tilt: Double? = this.givenTilt,
        paddings: MapPaddingsInterface? = this.paddings,
        visibleRegion: VisibleRegion? = this.visibleRegion,
    ) = MapCameraPosition(
        position = position ?: this.position,
        zoom = zoom ?: this.givenZoom,
        bearing = bearing ?: this.givenBearing,
        tilt = tilt ?: this.givenTilt,
        paddings = paddings ?: this.paddings,
        visibleRegion = visibleRegion ?: this.visibleRegion,
    )

    private fun zoomEquals(other: MapCameraPositionInterface): Boolean {
        val tolerance = 1e-2
        return abs(this.zoom - other.zoom) < tolerance
    }

    private fun bearingEquals(other: MapCameraPositionInterface): Boolean {
        val tolerance = 1e-2
        return abs(this.bearing - other.bearing) < tolerance
    }

    private fun tiltEquals(other: MapCameraPositionInterface): Boolean {
        val tolerance = 1e-2
        return abs(this.tilt - other.tilt) < tolerance
    }

    override fun hashCode(): Int {
        var result = this.position.hashCode()
        result = 31 * result + zoom.hashCode()
        result = 31 * result + bearing.hashCode()
        result = 31 * result + tilt.hashCode()
        result = 31 * result + paddings.hashCode()
        result = 31 * result + visibleRegion.hashCode()
        return result
    }

    companion object {
        val Default =
            MapCameraPosition(
                position =
                    GeoPoint(
                        latitude = 0.0,
                        longitude = 0.0,
                        altitude = 0.0,
                    ),
                zoom = 0.0,
                bearing = 0.0,
                tilt = 0.0,
            )
    }
}
