package com.mapconductor.core.polyline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapconductor.core.ComponentState
import com.mapconductor.core.StateMutationSignal
import com.mapconductor.core.features.GeoPointInterface
import java.io.Serializable

class PolylineState(
    points: List<GeoPointInterface>,
    id: String? = null,
    strokeColor: Color = Color.Black,
    strokeWidth: Dp = 1.dp,
    geodesic: Boolean = false,
    clickable: Boolean = true,
    zIndex: Int = 0,
    extra: Serializable? = null,
    onClick: OnPolylineEventHandler? = null,
    onAdded: OnPolylineAddedHandler? = null,
) : ComponentState {
    override val id =
        (
            id ?: polylineId(
                listOf(
                    listHashCode(points),
                    strokeColor.hashCode(),
                    strokeWidth.hashCode(),
                    geodesic.hashCode(),
                    clickable.hashCode(),
                    extra?.hashCode() ?: 0,
                ),
            )
        ).toString()

    /**
     * Writes to the fields below are announced here rather than discovered by
     * reading them all back. See [StateMutationSignal].
     */
    override val mutations = StateMutationSignal()

    var strokeColor by mutations.notifying(strokeColor)
    var strokeWidth by mutations.notifying(strokeWidth)
    var geodesic by mutations.notifying(geodesic)

    /**
     * タップを受け取るか。`false` ならこのポリラインはタップに対して透過し、
     * 下のオーバーレイ（無ければ地図クリック）へイベントが流れる。
     *
     * 判定はコアの [com.mapconductor.core.polyline.PolylineManager.find] が行うため、
     * どのプロバイダでも同じ挙動になる。描画には影響しないので [fingerPrint] には
     * 含めない（含めると値を変えるたびにオーバーレイが作り直される）。
     */
    var clickable by mutations.notifying(clickable)
    var zIndex by mutations.notifying(zIndex)
    var points by mutations.notifying<List<GeoPointInterface>>(points)
    var extra by mutations.notifying(extra)
    var onClick by mutations.notifying(onClick)

    /**
     * Called once each time this polyline goes from not being on the map to being on it:
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
    var onAdded: OnPolylineAddedHandler? = onAdded

    @Deprecated("Binary compatibility only; use the overload with onAdded.", level = DeprecationLevel.HIDDEN)
    constructor(
        points: List<GeoPointInterface>,
        id: String? = null,
        strokeColor: Color = Color.Black,
        strokeWidth: Dp = 1.dp,
        geodesic: Boolean = false,
        clickable: Boolean = true,
        zIndex: Int = 0,
        extra: Serializable? = null,
        onClick: OnPolylineEventHandler? = null,
    ) : this(
        points,
        id,
        strokeColor,
        strokeWidth,
        geodesic,
        clickable,
        zIndex,
        extra,
        onClick,
        null,
    )

    private fun polylineId(hashCodes: List<Int>): Int =
        hashCodes.reduce { result, hashCode ->
            31 * result + hashCode
        }

    override fun equals(other: Any?): Boolean {
        val otherState = (other as? PolylineState) ?: return false
        return hashCode() == otherState.hashCode()
    }

    override fun hashCode(): Int {
        var result = extra?.hashCode() ?: 0
        result = 31 * result + this@PolylineState.strokeColor.hashCode()
        result = 31 * result + this@PolylineState.strokeWidth.hashCode()
        result = 31 * result + geodesic.hashCode()
        result = 31 * result + clickable.hashCode()
        result = 31 * result + zIndex.hashCode()
        result = 31 * result + listHashCode(points)
        return result
    }

    fun copy(
        points: List<GeoPointInterface> = this.points,
        id: String? = this.id,
        strokeColor: Color = this.strokeColor,
        strokeWidth: Dp = this.strokeWidth,
        geodesic: Boolean = this.geodesic,
        clickable: Boolean = this.clickable,
        zIndex: Int = this.zIndex,
        extra: Serializable? = this.extra,
        onClick: OnPolylineEventHandler? = this.onClick,
        onAdded: OnPolylineAddedHandler? = this.onAdded,
    ): PolylineState =
        PolylineState(
            points = points,
            id = id,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
            geodesic = geodesic,
            clickable = clickable,
            zIndex = zIndex,
            extra = extra,
            onClick = onClick,
            onAdded = onAdded,
        )

    @Deprecated("Binary compatibility only; use the overload with onAdded.", level = DeprecationLevel.HIDDEN)
    fun copy(
        points: List<GeoPointInterface> = this.points,
        id: String? = this.id,
        strokeColor: Color = this.strokeColor,
        strokeWidth: Dp = this.strokeWidth,
        geodesic: Boolean = this.geodesic,
        clickable: Boolean = this.clickable,
        zIndex: Int = this.zIndex,
        extra: Serializable? = this.extra,
        onClick: OnPolylineEventHandler? = this.onClick,
    ): PolylineState =
        copy(
            points,
            id,
            strokeColor,
            strokeWidth,
            geodesic,
            clickable,
            zIndex,
            extra,
            onClick,
            onAdded,
        )

    private fun <T> listHashCode(list: List<T>): Int {
        var result = 0
        list.forEach {
            result = 31 * result + it.hashCode()
        }
        return result
    }

    fun fingerPrint(): PolylineFingerPrint =
        PolylineFingerPrint(
            id = this.id.hashCode(),
            strokeColor = this@PolylineState.strokeColor.hashCode(),
            strokeWidth = this@PolylineState.strokeWidth.hashCode(),
            geodesic = geodesic.hashCode(),
            zIndex = zIndex.hashCode(),
            points = listHashCode(points),
            extra = extra?.hashCode() ?: 0,
        )
}

data class PolylineFingerPrint(
    val id: Int,
    val strokeColor: Int,
    val strokeWidth: Int,
    val geodesic: Int,
    val zIndex: Int,
    val points: Int,
    val extra: Int,
)

class PolylineEvent(
    val state: PolylineState,
    clicked: GeoPointInterface,
) {
    // 生成時に wrap して [-180,180] / [-90,90] に正規化する（日付変更線対策）。
    // 正規化をここ（イベント型＝出口）に一元化することで、コア/独自 Conductor/将来のプロバイダの
    // どの配送経路を通っても wrap 漏れが起きない。ヒットテストの入力座標は wrap しないこと。
    val clicked: GeoPointInterface = clicked.wrap()
}

typealias OnPolylineEventHandler = (PolylineEvent) -> Unit

/** Receives the state whose polyline was just added to the map. See [PolylineState.onAdded]. */
typealias OnPolylineAddedHandler = (PolylineState) -> Unit
