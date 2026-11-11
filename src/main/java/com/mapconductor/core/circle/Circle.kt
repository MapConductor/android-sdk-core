package com.mapconductor.core.circle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapconductor.core.ComponentState
import com.mapconductor.core.StateMutationSignal
import com.mapconductor.core.features.GeoPointInterface
import java.io.Serializable

class CircleState(
    center: GeoPointInterface,
    radiusMeters: Double,
    geodesic: Boolean = true,
    clickable: Boolean = true,
    strokeColor: Color = Color.Red,
    strokeWidth: Dp = 1.dp,
    fillColor: Color =
        Color(
            red = 255,
            green = 255,
            blue = 255,
            alpha = 127,
        ),
    id: String? = null,
    zIndex: Int? = null,
    extra: Serializable? = null,
    onClick: OnCircleEventHandler? = null,
    onAdded: OnCircleAddedHandler? = null,
) : ComponentState {
    /**
     * Writes to the fields below are announced here rather than discovered by
     * reading them all back. See [StateMutationSignal].
     */
    override val mutations = StateMutationSignal()

    // One Compose snapshot record per circle, rather than one per attribute.
    private data class Values(
        val center: GeoPointInterface,
        val clickable: Boolean,
        val radiusMeters: Double,
        val geodesic: Boolean,
        val strokeColor: Color,
        val strokeWidth: Dp,
        val fillColor: Color,
        val extra: Serializable?,
        val zIndex: Int?,
        val onClick: OnCircleEventHandler?,
        val revision: Long = 0,
        val changedFields: Int = 0,
    ) {
        fun difference(other: Values): Int {
            var mask = 0
            if (center != other.center) mask = mask or (1 shl 0)
            if (clickable != other.clickable) mask = mask or (1 shl 3)
            if (radiusMeters != other.radiusMeters) mask = mask or (1 shl 1)
            if (geodesic != other.geodesic) mask = mask or (1 shl 2)
            if (strokeColor != other.strokeColor) mask = mask or (1 shl 4)
            if (strokeWidth != other.strokeWidth) mask = mask or (1 shl 5)
            if (fillColor != other.fillColor) mask = mask or (1 shl 6)
            if (extra != other.extra) mask = mask or (1 shl 7)
            if (zIndex != other.zIndex) mask = mask or (1 shl 8)
            return mask
        }
    }
    internal fun captureValues(): Any = values
    internal fun changeMaskSince(snapshot: Any, sameState: Boolean): Int {
        val applied = snapshot as Values
        val current = values
        if (current === applied) return 0
        return if (sameState && current.revision != applied.revision) current.changedFields
        else applied.difference(current)
    }
    internal fun snapshotFingerPrint(snapshot: Any): CircleFingerPrint = fingerPrint(snapshot as Values)
    private var values by androidx.compose.runtime.mutableStateOf(Values(center, clickable, radiusMeters, geodesic, strokeColor, strokeWidth, fillColor, extra, zIndex, onClick))

    private fun update(next: Values) {
        if (values == next) return
        values = next.copy(revision = values.revision + 1, changedFields = values.changedFields or values.difference(next))
        mutations.notifyMutated()
    }

    var center: GeoPointInterface
        get() = values.center
        set(value) { update(values.copy(center = value)) }
    var clickable: Boolean
        get() = values.clickable
        set(value) { update(values.copy(clickable = value)) }
    var radiusMeters: Double
        get() = values.radiusMeters
        set(value) { update(values.copy(radiusMeters = value)) }
    var geodesic: Boolean
        get() = values.geodesic
        set(value) { update(values.copy(geodesic = value)) }
    var strokeColor: Color
        get() = values.strokeColor
        set(value) { update(values.copy(strokeColor = value)) }
    var strokeWidth: Dp
        get() = values.strokeWidth
        set(value) { update(values.copy(strokeWidth = value)) }
    var fillColor: Color
        get() = values.fillColor
        set(value) { update(values.copy(fillColor = value)) }
    var extra: Serializable?
        get() = values.extra
        set(value) { update(values.copy(extra = value)) }
    var zIndex: Int?
        get() = values.zIndex
        set(value) { update(values.copy(zIndex = value)) }
    var onClick: OnCircleEventHandler?
        get() = values.onClick
        set(value) { update(values.copy(onClick = value)) }

    /**
     * Called once each time this circle goes from not being on the map to being on it:
     * after the map SDK's object for it has been created, or, when it is drawn in tiles,
     * after the URL template of those tiles has been handed to the map as a raster layer
     * (the tile images may still be loading then). Not called for an in-place change,
     * nor when it moves between native drawing and tiles while staying on the map;
     * called again if it is removed and added again. Clearing or destroying the map calls
     * nothing.
     *
     * Runs on the main thread once the map has finished adding the overlays it was given.
     * A callback that throws is logged and does not stop the other overlays' callbacks.
     *
     * Like `onClick`, it is not part of the id, fingerprint, equality or hash: replacing
     * it redraws nothing. If the same state is shown on two maps, each map calls it.
     */
    var onAdded: OnCircleAddedHandler? = onAdded

    @Deprecated("Binary compatibility only; use the overload with onAdded.", level = DeprecationLevel.HIDDEN)
    constructor(
        center: GeoPointInterface,
        radiusMeters: Double,
        geodesic: Boolean = true,
        clickable: Boolean = true,
        strokeColor: Color = Color.Red,
        strokeWidth: Dp = 1.dp,
        fillColor: Color =
            Color(
                red = 255,
                green = 255,
                blue = 255,
                alpha = 127,
            ),
        id: String? = null,
        zIndex: Int? = null,
        extra: Serializable? = null,
        onClick: OnCircleEventHandler? = null,
    ) : this(
        center,
        radiusMeters,
        geodesic,
        clickable,
        strokeColor,
        strokeWidth,
        fillColor,
        id,
        zIndex,
        extra,
        onClick,
        null,
    )

    override val id =
        (
            id ?: circleId(
                listOf(
                    center.hashCode(),
                    radiusMeters.hashCode(),
                    clickable.hashCode(),
                    geodesic.hashCode(),
                    extra?.hashCode() ?: 0,
                    strokeColor.hashCode(),
                    strokeWidth.hashCode(),
                    fillColor.hashCode(),
                    zIndex.hashCode(),
                ),
            )
        ).toString()

    private fun circleId(hashCodes: List<Int>): Int =
        hashCodes.reduce { result, hashCode ->
            31 * result + hashCode
        }

    fun fingerPrint(): CircleFingerPrint = fingerPrint(values)

    private fun fingerPrint(snapshot: Values): CircleFingerPrint =
        CircleFingerPrint(
            id = this.id.hashCode(),
            center = snapshot.center.hashCode(),
            radiusMeters = snapshot.radiusMeters.hashCode(),
            clickable = snapshot.clickable.hashCode(),
            geodesic = snapshot.geodesic.hashCode(),
            strokeColor = snapshot.strokeColor.hashCode(),
            strokeWidth = snapshot.strokeWidth.hashCode(),
            fillColor = snapshot.fillColor.hashCode(),
            zIndex = snapshot.zIndex.hashCode(),
            extra = snapshot.extra?.hashCode() ?: 0,
        )

    fun copy(
        center: GeoPointInterface = this.center,
        radiusMeters: Double = this.radiusMeters,
        geodesic: Boolean = this.geodesic,
        clickable: Boolean = this.clickable,
        strokeColor: Color = this.strokeColor,
        strokeWidth: Dp = this.strokeWidth,
        fillColor: Color = this.fillColor,
        id: String? = this.id,
        zIndex: Int? = this.zIndex,
        extra: Serializable? = this.extra,
        onClick: OnCircleEventHandler? = this.onClick,
        onAdded: OnCircleAddedHandler? = this.onAdded,
    ): CircleState =
        CircleState(
            center = center,
            clickable = clickable,
            radiusMeters = radiusMeters,
            geodesic = geodesic,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
            fillColor = fillColor,
            id = id,
            zIndex = zIndex,
            extra = extra,
            onClick = onClick,
            onAdded = onAdded,
        )

    @Deprecated("Binary compatibility only; use the overload with onAdded.", level = DeprecationLevel.HIDDEN)
    fun copy(
        center: GeoPointInterface = this.center,
        radiusMeters: Double = this.radiusMeters,
        geodesic: Boolean = this.geodesic,
        clickable: Boolean = this.clickable,
        strokeColor: Color = this.strokeColor,
        strokeWidth: Dp = this.strokeWidth,
        fillColor: Color = this.fillColor,
        id: String? = this.id,
        zIndex: Int? = this.zIndex,
        extra: Serializable? = this.extra,
        onClick: OnCircleEventHandler? = this.onClick,
    ): CircleState =
        copy(
            center,
            radiusMeters,
            geodesic,
            clickable,
            strokeColor,
            strokeWidth,
            fillColor,
            id,
            zIndex,
            extra,
            onClick,
            onAdded,
        )

    override fun equals(other: Any?): Boolean {
        val otherState = (other as? CircleState) ?: return false
        return hashCode() == otherState.hashCode()
    }

    override fun hashCode(): Int {
        var result = extra?.hashCode() ?: 0
        result = 31 * result + center.hashCode()
        result = 31 * result + clickable.hashCode()
        result = 31 * result + geodesic.hashCode()
        result = 31 * result + radiusMeters.hashCode()
        result = 31 * result + strokeColor.hashCode()
        result = 31 * result + strokeWidth.hashCode()
        result = 31 * result + fillColor.hashCode()
        result = 31 * result + zIndex.hashCode()
        return result
    }
}

data class CircleFingerPrint(
    val id: Int,
    val center: Int,
    val radiusMeters: Int,
    val clickable: Int,
    val geodesic: Int,
    val strokeColor: Int,
    val strokeWidth: Int,
    val fillColor: Int,
    val zIndex: Int,
    val extra: Int,
)

class CircleEvent(
    val state: CircleState,
    clicked: GeoPointInterface,
) {
    // 生成時に wrap して [-180,180] / [-90,90] に正規化する（日付変更線対策）。
    // 正規化をここ（イベント型＝出口）に一元化することで、どの配送経路でも wrap 漏れが起きない。
    val clicked: GeoPointInterface = clicked.wrap()
}

typealias OnCircleEventHandler = (CircleEvent) -> Unit

/** Receives the state whose circle was just added to the map. See [CircleState.onAdded]. */
typealias OnCircleAddedHandler = (CircleState) -> Unit
