package com.mapconductor.core.polygon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapconductor.core.ComponentState
import com.mapconductor.core.StateMutationSignal
import com.mapconductor.core.features.GeoPointInterface
import java.io.Serializable

class PolygonState(
    points: List<GeoPointInterface>,
    holes: List<List<GeoPointInterface>> = emptyList(),
    id: String? = null,
    strokeColor: Color = Color.Black,
    strokeWidth: Dp = 1.dp,
    fillColor: Color = Color.Transparent,
    geodesic: Boolean = false,
    clickable: Boolean = true,
    zIndex: Int = 0,
    extra: Serializable? = null,
    onClick: OnPolygonEventHandler? = null,
    onAdded: OnPolygonAddedHandler? = null,
) : ComponentState {
    override val id =
        (
            id ?: polygonId(
                listOf(
                    listHashCode(points),
                    nestedListHashCode(holes),
                    strokeColor.hashCode(),
                    strokeWidth.hashCode(),
                    fillColor.hashCode(),
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

    // One Compose snapshot record per polygon rather than one per attribute, as
    // in CircleState: a mutableStateOf is three objects, and there were nine.
    private data class Values(
        val points: List<GeoPointInterface>,
        val holes: List<List<GeoPointInterface>>,
        val strokeColor: Color,
        val strokeWidth: Dp,
        val fillColor: Color,
        val geodesic: Boolean,
        val clickable: Boolean,
        val zIndex: Int,
        val extra: Serializable?,
        val onClick: OnPolygonEventHandler?,
    )

    private var values by mutableStateOf(
        Values(points, holes, strokeColor, strokeWidth, fillColor, geodesic, clickable, zIndex, extra, onClick),
    )

    // A write compares only the field written, as the per-attribute states did
    // (not the whole record, which would compare the points to change a colour),
    // and is serialized so writes to two fields from two threads both land.
    private inline fun write(next: (Values) -> Values?) {
        synchronized(this) { values = next(values) ?: return }
        mutations.notifyMutated()
    }

    var strokeColor: Color
        get() = values.strokeColor
        set(value) = write { if (it.strokeColor == value) null else it.copy(strokeColor = value) }
    var strokeWidth: Dp
        get() = values.strokeWidth
        set(value) = write { if (it.strokeWidth == value) null else it.copy(strokeWidth = value) }
    var fillColor: Color
        get() = values.fillColor
        set(value) = write { if (it.fillColor == value) null else it.copy(fillColor = value) }
    var geodesic: Boolean
        get() = values.geodesic
        set(value) = write { if (it.geodesic == value) null else it.copy(geodesic = value) }

    /**
     * タップを受け取るか。`false` ならこのポリゴンはタップに対して透過し、
     * 下のオーバーレイ（無ければ地図クリック）へイベントが流れる。
     *
     * 判定はコアの [com.mapconductor.core.polygon.PolygonManager.find] が行うため、
     * どのプロバイダでも同じ挙動になる。描画には影響しないので [fingerPrint] には
     * 含めない（含めると値を変えるたびにオーバーレイが作り直される）。
     */
    var clickable: Boolean
        get() = values.clickable
        set(value) = write { if (it.clickable == value) null else it.copy(clickable = value) }
    var zIndex: Int
        get() = values.zIndex
        set(value) = write { if (it.zIndex == value) null else it.copy(zIndex = value) }

    /**
     * 外周の頂点。
     *
     * state が見ているのはこのプロパティへの代入だけ。渡したリストをその場で書き換えても
     * 描き直されない（`mutableStateListOf` を渡して `add` / `set` する使い方は効かない）。
     * 形を変えるときは新しいリストを代入する:
     *
     * ```
     * polygonState.points = polygonState.points.toMutableList().apply { this[i] = moved }
     * ```
     *
     * 内容の等しいリストの代入は無視される。その場で書き換えた同じリストを代入し直しても、
     * 前の値と同じものなので何も起きない。
     */
    var points: List<GeoPointInterface>
        get() = values.points
        set(value) = write { if (it.points == value) null else it.copy(points = value) }

    /**
     * 穴（内周）の頂点。[points] と同じく、変えるときは新しいリストを代入する。
     * 内側のリストをその場で書き換えても描き直されない。
     */
    var holes: List<List<GeoPointInterface>>
        get() = values.holes
        set(value) = write { if (it.holes == value) null else it.copy(holes = value) }
    var extra: Serializable?
        get() = values.extra
        set(value) = write { if (it.extra == value) null else it.copy(extra = value) }
    var onClick: OnPolygonEventHandler?
        get() = values.onClick
        set(value) = write { if (it.onClick == value) null else it.copy(onClick = value) }

    /**
     * Called once each time this polygon goes from not being on the map to being on it:
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
    var onAdded: OnPolygonAddedHandler? = onAdded

    @Deprecated("Binary compatibility only; use the overload with onAdded.", level = DeprecationLevel.HIDDEN)
    constructor(
        points: List<GeoPointInterface>,
        holes: List<List<GeoPointInterface>> = emptyList(),
        id: String? = null,
        strokeColor: Color = Color.Black,
        strokeWidth: Dp = 1.dp,
        fillColor: Color = Color.Transparent,
        geodesic: Boolean = false,
        clickable: Boolean = true,
        zIndex: Int = 0,
        extra: Serializable? = null,
        onClick: OnPolygonEventHandler? = null,
    ) : this(
        points,
        holes,
        id,
        strokeColor,
        strokeWidth,
        fillColor,
        geodesic,
        clickable,
        zIndex,
        extra,
        onClick,
        null,
    )

    private fun polygonId(hashCodes: List<Int>): Int =
        hashCodes.reduce { result, hashCode ->
            31 * result + hashCode
        }

    override fun equals(other: Any?): Boolean {
        val otherState = (other as? PolygonState) ?: return false
        return hashCode() == otherState.hashCode()
    }

    override fun hashCode(): Int {
        var result = extra?.hashCode() ?: 0
        result = 31 * result + this@PolygonState.strokeColor.hashCode()
        result = 31 * result + this@PolygonState.strokeWidth.hashCode()
        result = 31 * result + this@PolygonState.fillColor.hashCode()
        result = 31 * result + geodesic.hashCode()
        result = 31 * result + clickable.hashCode()
        result = 31 * result + zIndex.hashCode()
        result = 31 * result + points.hashCode()
        result = 31 * result + holes.hashCode()
        return result
    }

    private fun <T> listHashCode(list: List<T>): Int {
        var result = 0
        list.forEach {
            result = 31 * result + it.hashCode()
        }
        return result
    }

    private fun <T> nestedListHashCode(list: List<List<T>>): Int {
        var result = 0
        list.forEach { inner ->
            result = 31 * result + listHashCode(inner)
        }
        return result
    }

    fun fingerPrint(): PolygonFingerPrint =
        PolygonFingerPrint(
            id = this.id.hashCode(),
            strokeColor = this@PolygonState.strokeColor.hashCode(),
            strokeWidth = this@PolygonState.strokeWidth.hashCode(),
            fillColor = this@PolygonState.fillColor.hashCode(),
            geodesic = geodesic.hashCode(),
            zIndex = zIndex,
            points = listHashCode(points),
            holes = nestedListHashCode(holes),
            extra = extra?.hashCode() ?: 0,
        )

    /**
     * Copies this state, carrying every property over unless overridden.
     *
     * [id] is not carried over: it is recomputed from the copied contents unless
     * one is passed. Keeping it made the renderer, which keys overlays by id,
     * treat the copy as an update of the original, so an overlay added with
     * `copy()` replaced the original instead of appearing next to it. Pass
     * `id = state.id` to update the same overlay.
     */
    fun copy(
        points: List<GeoPointInterface> = this.points,
        holes: List<List<GeoPointInterface>> = this.holes,
        id: String? = null,
        strokeColor: Color = this.strokeColor,
        strokeWidth: Dp = this.strokeWidth,
        fillColor: Color = this.fillColor,
        geodesic: Boolean = this.geodesic,
        clickable: Boolean = this.clickable,
        zIndex: Int = this.zIndex,
        extra: Serializable? = this.extra,
        onClick: OnPolygonEventHandler? = this.onClick,
        onAdded: OnPolygonAddedHandler? = this.onAdded,
    ): PolygonState =
        PolygonState(
            points = points,
            holes = holes,
            id = id,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
            fillColor = fillColor,
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
        holes: List<List<GeoPointInterface>> = this.holes,
        id: String? = null,
        strokeColor: Color = this.strokeColor,
        strokeWidth: Dp = this.strokeWidth,
        fillColor: Color = this.fillColor,
        geodesic: Boolean = this.geodesic,
        clickable: Boolean = this.clickable,
        zIndex: Int = this.zIndex,
        extra: Serializable? = this.extra,
        onClick: OnPolygonEventHandler? = this.onClick,
    ): PolygonState =
        copy(
            points,
            holes,
            id,
            strokeColor,
            strokeWidth,
            fillColor,
            geodesic,
            clickable,
            zIndex,
            extra,
            onClick,
            onAdded,
        )
}

data class PolygonFingerPrint(
    val id: Int,
    val strokeColor: Int,
    val strokeWidth: Int,
    val fillColor: Int,
    val geodesic: Int,
    val zIndex: Int,
    val points: Int,
    val holes: Int,
    val extra: Int,
)

class PolygonEvent(
    val state: PolygonState,
    clicked: GeoPointInterface,
) {
    // 生成時に wrap して [-180,180] / [-90,90] に正規化する（日付変更線対策）。
    // 正規化をここ（イベント型＝出口）に一元化することで、どの配送経路でも wrap 漏れが起きない。
    val clicked: GeoPointInterface = clicked.wrap()
}

typealias OnPolygonEventHandler = (PolygonEvent) -> Unit

/** Receives the state whose polygon was just added to the map. See [PolygonState.onAdded]. */
typealias OnPolygonAddedHandler = (PolygonState) -> Unit
