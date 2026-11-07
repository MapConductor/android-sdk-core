package com.mapconductor.core.raster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.mapconductor.core.ComponentState
import com.mapconductor.core.StateMutationSignal

class RasterLayerState(
    source: RasterLayerSource,
    opacity: Float = 1.0f,
    visible: Boolean = true,
    zIndex: Int = 0,
    userAgent: String = DEFAULT_USER_AGENT,
    debug: Boolean = false,
    id: String? = null,
    extraHeaders: Map<String, String>? = null,
    onAdded: OnRasterLayerAddedHandler? = null,
) : ComponentState {
    override val id =
        (
            id ?: rasterLayerId(
                listOf(
                    source.hashCode(),
                    opacity.hashCode(),
                    visible.hashCode(),
                    debug.hashCode(),
                    extraHeaders?.hashCode() ?: 0,
                ),
            )
        ).toString()

    /**
     * Writes to the fields below are announced here rather than discovered by
     * reading them all back. See [StateMutationSignal].
     */
    override val mutations = StateMutationSignal()

    var source by mutations.notifying(source)
    var opacity by mutations.notifying(opacity)
    var visible by mutations.notifying(visible)
    var zIndex by mutations.notifying(zIndex)
    var userAgent by mutations.notifying(userAgent)
    var debug by mutations.notifying(debug)
    var extraHeaders by mutations.notifying(extraHeaders)

    /**
     * Called once each time this raster layer goes from not being on the map to being on it:
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
    var onAdded: OnRasterLayerAddedHandler? = onAdded

    @Deprecated("Binary compatibility only; use the overload with onAdded.", level = DeprecationLevel.HIDDEN)
    constructor(
        source: RasterLayerSource,
        opacity: Float = 1.0f,
        visible: Boolean = true,
        zIndex: Int = 0,
        userAgent: String = DEFAULT_USER_AGENT,
        debug: Boolean = false,
        id: String? = null,
        extraHeaders: Map<String, String>? = null,
    ) : this(
        source,
        opacity,
        visible,
        zIndex,
        userAgent,
        debug,
        id,
        extraHeaders,
        null,
    )

    private fun rasterLayerId(hashCodes: List<Int>): Int =
        hashCodes.reduce { result, hashCode ->
            31 * result + hashCode
        }

    override fun equals(other: Any?): Boolean {
        val otherState = (other as? RasterLayerState) ?: return false
        return hashCode() == otherState.hashCode()
    }

    override fun hashCode(): Int {
        var result = source.hashCode()
        result = 31 * result + opacity.hashCode()
        result = 31 * result + visible.hashCode()
        result = 31 * result + zIndex.hashCode()
        result = 31 * result + debug.hashCode()
        result = 31 * result + (extraHeaders?.hashCode() ?: 0)
        result = 31 * result + userAgent.hashCode()
        return result
    }

    fun copy(
        source: RasterLayerSource = this.source,
        opacity: Float = this.opacity,
        visible: Boolean = this.visible,
        zIndex: Int = this.zIndex,
        debug: Boolean = this.debug,
        userAgent: String = this.userAgent,
        id: String? = this.id,
        extraHeaders: Map<String, String>? = this.extraHeaders,
        onAdded: OnRasterLayerAddedHandler? = this.onAdded,
    ): RasterLayerState =
        RasterLayerState(
            source = source,
            opacity = opacity,
            visible = visible,
            zIndex = zIndex,
            userAgent = userAgent,
            debug = debug,
            id = id,
            extraHeaders = extraHeaders,
            onAdded = onAdded,
        )

    @Deprecated("Binary compatibility only; use the overload with onAdded.", level = DeprecationLevel.HIDDEN)
    fun copy(
        source: RasterLayerSource = this.source,
        opacity: Float = this.opacity,
        visible: Boolean = this.visible,
        zIndex: Int = this.zIndex,
        debug: Boolean = this.debug,
        userAgent: String = this.userAgent,
        id: String? = this.id,
        extraHeaders: Map<String, String>? = this.extraHeaders,
    ): RasterLayerState =
        copy(
            source,
            opacity,
            visible,
            zIndex,
            debug,
            userAgent,
            id,
            extraHeaders,
            onAdded,
        )

    fun fingerPrint(): RasterLayerFingerPrint =
        RasterLayerFingerPrint(
            id = id.hashCode(),
            source = source.hashCode(),
            opacity = opacity.hashCode(),
            visible = visible.hashCode(),
            zIndex = zIndex.hashCode(),
            userAgent = userAgent.hashCode(),
            debug = debug.hashCode(),
            extra = extraHeaders?.hashCode() ?: 0,
        )

    companion object {
        /**
         * `userAgent` を明示しなかったときに入る値。
         *
         * 定数として公開しているのは、プロバイダ側が「利用者が本当に指定したのか、
         * 既定のまま来ただけなのか」を判断するため。ヘッダを載せるのに追加のコストが
         * 要るプロバイダは、既定値のままならそのコストを払わない。
         * ios-sdk の `RasterLayerState.defaultUserAgent` / react-sdk の
         * `DEFAULT_RASTER_LAYER_USER_AGENT` と同じ値。
         */
        const val DEFAULT_USER_AGENT = "MapConductor/RasterLayerAgent(https://mapconductor.com)"
    }
}

data class RasterLayerFingerPrint(
    val id: Int,
    val source: Int,
    val opacity: Int,
    val visible: Int,
    val zIndex: Int,
    val userAgent: Int,
    val debug: Int,
    val extra: Int,
)

data class RasterLayerEvent(
    val state: RasterLayerState,
)

typealias OnRasterLayerEventHandler = (RasterLayerEvent) -> Unit

/** Receives the state whose layer was just added to the map. See [RasterLayerState.onAdded]. */
typealias OnRasterLayerAddedHandler = (RasterLayerState) -> Unit
