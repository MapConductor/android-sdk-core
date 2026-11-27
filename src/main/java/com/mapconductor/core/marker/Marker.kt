package com.mapconductor.core.marker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.mapconductor.core.ComponentState
import com.mapconductor.core.StateMutationSignal
import com.mapconductor.core.features.GeoPointInterface
import java.io.ByteArrayOutputStream
import java.io.Serializable
import android.graphics.Bitmap

// ------- Core Types ----------
class MarkerState(
    position: GeoPointInterface,
    id: String? = null,
    var extra: Serializable? = null,
    icon: MarkerIconInterface? = null,
    animation: MarkerAnimation? = null,
    zIndex: Int? = null,
    clickable: Boolean = true,
    draggable: Boolean = false,
    onClick: OnMarkerEventHandler? = null,
    onDragStart: OnMarkerEventHandler? = null,
    onDrag: OnMarkerEventHandler? = null,
    onDragEnd: OnMarkerEventHandler? = null,
    onAnimateStart: OnMarkerEventHandler? = null,
    onAnimateEnd: OnMarkerEventHandler? = null,
    onAdded: OnMarkerEventHandler? = null,
) : ComponentState {
    override val id =
        (
            id ?: markerId(
                listOf(
                    position.hashCode(),
                    extra?.hashCode() ?: 0,
                    icon?.hashCode() ?: 0,
                    clickable.hashCode(),
                    draggable.hashCode(),
                    animation?.hashCode() ?: 0,
                ),
            )
        ).toString()

    private fun markerId(hashCodes: List<Int>): Int =
        hashCodes.reduce { result, hashCode ->
            31 * result + hashCode
        }

    /**
     * Writes to the fields below are announced here rather than discovered by
     * reading them all back. See [StateMutationSignal].
     */
    override val mutations = StateMutationSignal()

    // One Compose snapshot record per marker rather than one per attribute, as
    // in CircleState: a mutableStateOf is three objects, and there were thirteen.
    private data class Values(
        val position: GeoPointInterface,
        val icon: MarkerIconInterface?,
        val animation: MarkerAnimation?,
        val zIndex: Int?,
        val clickable: Boolean,
        val draggable: Boolean,
        val onClick: OnMarkerEventHandler?,
        val onDragStart: OnMarkerEventHandler?,
        val onDrag: OnMarkerEventHandler?,
        val onDragEnd: OnMarkerEventHandler?,
        val onAnimateStart: OnMarkerEventHandler?,
        val onAnimateEnd: OnMarkerEventHandler?,
        /** [iconRevision] の中身。URL の画像が届くたびに増える。 */
        val iconRevision: Int = 0,
    )

    private var values by mutableStateOf(
        Values(
            position, icon, animation, zIndex, clickable, draggable,
            onClick, onDragStart, onDrag, onDragEnd, onAnimateStart, onAnimateEnd,
        ),
    )

    // A write compares only the field written, as the per-attribute states did,
    // and is serialized so writes to two fields from two threads both land (an
    // animation moving the marker while the app swaps its icon, say).
    private inline fun write(next: (Values) -> Values?) {
        synchronized(this) { values = next(values) ?: return }
        mutations.notifyMutated()
    }

    internal val iconRevisionValue: Int get() = values.iconRevision

    var icon: MarkerIconInterface?
        get() = values.icon
        set(value) {
            val changed = values.icon != value
            write { if (it.icon == value) null else it.copy(icon = value) }
            if (changed) redrawWhenImageLoaded(value)
        }
    var clickable: Boolean
        get() = values.clickable
        set(value) = write { if (it.clickable == value) null else it.copy(clickable = value) }
    var draggable: Boolean
        get() = values.draggable
        set(value) = write { if (it.draggable == value) null else it.copy(draggable = value) }
    var onClick: OnMarkerEventHandler?
        get() = values.onClick
        set(value) = write { if (it.onClick == value) null else it.copy(onClick = value) }
    var onDragStart: OnMarkerEventHandler?
        get() = values.onDragStart
        set(value) = write { if (it.onDragStart == value) null else it.copy(onDragStart = value) }
    var onDrag: OnMarkerEventHandler?
        get() = values.onDrag
        set(value) = write { if (it.onDrag == value) null else it.copy(onDrag = value) }
    var onDragEnd: OnMarkerEventHandler?
        get() = values.onDragEnd
        set(value) = write { if (it.onDragEnd == value) null else it.copy(onDragEnd = value) }
    var onAnimateStart: OnMarkerEventHandler?
        get() = values.onAnimateStart
        set(value) = write { if (it.onAnimateStart == value) null else it.copy(onAnimateStart = value) }
    var onAnimateEnd: OnMarkerEventHandler?
        get() = values.onAnimateEnd
        set(value) = write { if (it.onAnimateEnd == value) null else it.copy(onAnimateEnd = value) }

    /**
     * Called once each time this marker goes from not being on the map to being on it:
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
    var onAdded: OnMarkerEventHandler? = onAdded
    var zIndex: Int?
        get() = values.zIndex
        set(value) = write { if (it.zIndex == value) null else it.copy(zIndex = value) }

    init {
        redrawWhenImageLoaded(icon)
    }

    /**
     * URL の画像がまだ届いていないアイコンなら、届いたときに描き直させる。
     *
     * アイコンの見た目（hashCode）は画像が届いた時点で変わるが、[icon] 自体は書き換わらない
     * ので、ここで知らせる。そのときもまだ同じアイコンを付けていれば、だけ。
     * 読み込みのスレッドから呼ばれるが、[StateMutationSignal.notifyMutated] はどのスレッドからでもよい。
     */
    private fun redrawWhenImageLoaded(icon: MarkerIconInterface?) {
        val image = icon as? ImageIcon ?: return
        image.whenImageLoaded {
            write { if (it.icon !== image) null else it.copy(iconRevision = it.iconRevision + 1) }
        }
    }

    @Deprecated("Binary compatibility only; use the overload with onAdded.", level = DeprecationLevel.HIDDEN)
    constructor(
        position: GeoPointInterface,
        id: String? = null,
        extra: Serializable? = null,
        icon: MarkerIconInterface? = null,
        animation: MarkerAnimation? = null,
        zIndex: Int? = null,
        clickable: Boolean = true,
        draggable: Boolean = false,
        onClick: OnMarkerEventHandler? = null,
        onDragStart: OnMarkerEventHandler? = null,
        onDrag: OnMarkerEventHandler? = null,
        onDragEnd: OnMarkerEventHandler? = null,
        onAnimateStart: OnMarkerEventHandler? = null,
        onAnimateEnd: OnMarkerEventHandler? = null,
    ) : this(
        position,
        id,
        extra,
        icon,
        animation,
        zIndex,
        clickable,
        draggable,
        onClick,
        onDragStart,
        onDrag,
        onDragEnd,
        onAnimateStart,
        onAnimateEnd,
        null,
    )

    fun animate(animation: MarkerAnimation?) {
        write { if (it.animation == animation) null else it.copy(animation = animation) }
    }

    fun getAnimation(): MarkerAnimation? = values.animation

    var position: GeoPointInterface
        get() = values.position
        set(value) = write { if (it.position == value) null else it.copy(position = value) }

    /**
     * Copies this state, carrying every property over unless overridden.
     *
     * [animation] is copied like everything else. It used to be silently
     * dropped — the parameter did not exist and the new instance was built
     * without one — so `marker.copy(position = p)` on a bouncing marker
     * returned a still one. Nothing about a copy implies "stop animating", and
     * ios-sdk / react-sdk both carry it over.
     *
     * [id] is not carried over: it is recomputed from the copied contents unless
     * one is passed. Keeping it made the renderer, which keys markers by id,
     * treat the copy as an update of the original, so a marker added with
     * `copy()` replaced the original instead of appearing next to it. Pass
     * `id = state.id` to move the same marker.
     */
    fun copy(
        id: String? = null,
        position: GeoPointInterface = this.position,
        extra: Serializable? = this.extra,
        icon: MarkerIconInterface? = this.icon,
        animation: MarkerAnimation? = this.getAnimation(),
        zIndex: Int? = this.zIndex,
        clickable: Boolean? = this.clickable,
        draggable: Boolean? = this.draggable,
        onClick: OnMarkerEventHandler? = this.onClick,
        onDragStart: OnMarkerEventHandler? = this.onDragStart,
        onDrag: OnMarkerEventHandler? = this.onDrag,
        onDragEnd: OnMarkerEventHandler? = this.onDragEnd,
        onAnimateStart: OnMarkerEventHandler? = this.onAnimateStart,
        onAnimateEnd: OnMarkerEventHandler? = this.onAnimateEnd,
        onAdded: OnMarkerEventHandler? = this.onAdded,
    ): MarkerState =
        MarkerState(
            id = id,
            position = position,
            extra = extra,
            icon = icon,
            animation = animation,
            zIndex = zIndex,
            clickable = clickable ?: this.clickable,
            draggable = draggable ?: this.draggable,
            onClick = onClick,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onAnimateStart = onAnimateStart,
            onAnimateEnd = onAnimateEnd,
            onAdded = onAdded,
        )

    @Deprecated("Binary compatibility only; use the overload with onAdded.", level = DeprecationLevel.HIDDEN)
    fun copy(
        id: String? = null,
        position: GeoPointInterface = this.position,
        extra: Serializable? = this.extra,
        icon: MarkerIconInterface? = this.icon,
        animation: MarkerAnimation? = this.getAnimation(),
        zIndex: Int? = this.zIndex,
        clickable: Boolean? = this.clickable,
        draggable: Boolean? = this.draggable,
        onClick: OnMarkerEventHandler? = this.onClick,
        onDragStart: OnMarkerEventHandler? = this.onDragStart,
        onDrag: OnMarkerEventHandler? = this.onDrag,
        onDragEnd: OnMarkerEventHandler? = this.onDragEnd,
        onAnimateStart: OnMarkerEventHandler? = this.onAnimateStart,
        onAnimateEnd: OnMarkerEventHandler? = this.onAnimateEnd,
    ): MarkerState =
        copy(
            id,
            position,
            extra,
            icon,
            animation,
            zIndex,
            clickable,
            draggable,
            onClick,
            onDragStart,
            onDrag,
            onDragEnd,
            onAnimateStart,
            onAnimateEnd,
            onAdded,
        )

    override fun equals(other: Any?): Boolean {
        val otherState = (other as? MarkerState) ?: return false
        return hashCode() == otherState.hashCode()
    }

    override fun hashCode(): Int {
        var result = extra?.hashCode() ?: 0
        result = 31 * result + clickable.hashCode()
        result = 31 * result + draggable.hashCode()
        val position = values.position
        result = 31 * result + position.latitude.hashCode()
        result = 31 * result + position.longitude.hashCode()
        result = 31 * result + position.altitude.hashCode()
        result = 31 * result + (icon?.hashCode() ?: 0)
        result = 31 * result + zIndex.hashCode()
        return result
    }

    fun fingerPrint(): MarkerFingerPrint =
        MarkerFingerPrint(
            this.id.hashCode(),
            icon.hashCode(),
            clickable.hashCode(),
            draggable.hashCode(),
            values.position.latitude.hashCode(),
            values.position.longitude.hashCode(),
            values.animation?.hashCode() ?: 1,
            zIndex.hashCode(),
        )
}

data class MarkerFingerPrint(
    val id: Int,
    val icon: Int?,
    val clickable: Int,
    val draggable: Int,
    val latitude: Int,
    val longitude: Int,
    val animation: Int?,
    val zIndex: Int,
)
typealias OnMarkerEventHandler = (MarkerState) -> Unit

data class BitmapIcon(
    val bitmap: Bitmap,
    val anchor: Offset,
    val size: Size,
) {
    fun toByteArray(): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return outputStream.toByteArray()
    }
}
