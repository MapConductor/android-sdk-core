package com.mapconductor.core.map

import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.tileserver.LocalTileServer
import com.mapconductor.core.tileserver.TileServerRegistry

/**
 * What a map offers the style installed on it, for every provider at once.
 *
 * Provider-agnostic by construction: the three things a style needs that
 * differ per backend are all reached through seams every provider already
 * fills in — the service registry for what the map can do, the controller's
 * overlay controllers for mounting tiles, and the controller's style-loaded
 * listeners for putting an adjustment back after a reload. Adding a twelfth
 * provider needs nothing here.
 */
class MapViewStyleHost(
    /**
     * What this map can do. The registry rather than the state, because
     * React Native's wrapper has one and no state -- and the registry is
     * all a style ever reads. ios-sdk and js-sdk-core take the same.
     */
    override val serviceRegistry: MapServiceRegistry,
    private val controller: BaseMapViewController,
) : MapStyleHost {
    private var installation: MapStyleInstallation? = null
    private var installedKey: String? = null
    private var diagnosticsHandler: ((List<String>) -> Unit)? = null

    /** How many updates in a row the installed style has refused. See [apply]. */
    private var refusedUpdates = 0

    /**
     * Keeps [style] installed on this map.
     *
     * Called on every recomposition (and on every React Native prop update),
     * so most calls do nothing. Three things have to line up and none of
     * them is the update:
     *
     * - **A style whose key did not change must be left alone.** A style
     *   built inline is a new instance every time — which is exactly what a
     *   colour picker produces — so comparing instances would reinstall on
     *   every unrelated state change.
     * - **A changed style is usually an adjustment, not a new style.**
     *   Disposing and installing again hands the map a document and reloads
     *   it, which is the cost this whole design exists to avoid. What is
     *   installed is offered the new one first and only replaced if it
     *   refuses.
     * - **Null means take it off.** An app that stops passing a style gets
     *   the map it had before, not the one the style left behind.
     */
    fun apply(
        style: MapViewStyle?,
        onDiagnostics: ((List<String>) -> Unit)? = null,
    ) {
        diagnosticsHandler = onDiagnostics
        if (style == null) {
            dispose()
            return
        }
        val current = installation
        if (current == null) {
            installation = style.install(this)
            installedKey = style.key
            return
        }
        // Nothing changed at all. The key covers the document *and* the
        // rules, so an equal key means there is no work -- and doing it
        // anyway is not merely wasteful: recompiling re-reports the
        // diagnostics, an app that renders them sets state, that recomposes,
        // and the two spin against each other.
        if (installedKey == style.key) return

        // The adjustments moved. Offer them to what is installed: taking
        // them in place is what keeps a slider from reloading the map.
        if (current.update(style)) {
            installedKey = style.key
            refusedUpdates = 0
            return
        }

        // Refused -- correct, only slower. Refusing every time means the
        // style cannot recognise its own successor and the map is rebuilt
        // on each change; the usual cause is a value the style compares by
        // identity being rebuilt too, a rasteriser constructed inline
        // rather than held in `remember`.
        refusedUpdates++
        if (refusedUpdates == CHURN_THRESHOLD) {
            MapDiagnostics.sink.log(
                "style: the installed style has refused $CHURN_THRESHOLD updates in a row, so " +
                    "the map is being torn down and rebuilt on every change. Hold the rasteriser " +
                    "(and anything else the style compares by identity) in `remember` rather " +
                    "than rebuilding it.",
            )
        }
        current.dispose()
        installation = style.install(this)
        installedKey = style.key
    }

    /** Takes the style off. The caller does this when the view goes. */
    fun dispose() {
        installation?.dispose()
        installation = null
        installedKey = null
    }

    override val tileServer: LocalTileServer
        get() = TileServerRegistry.get()

    override val vectorStyleUrl: String?
        get() = serviceRegistry.get(VectorStyleSupportKey)?.currentStyleUrl

    override fun onStyleLoaded(block: () -> Unit): MapStyleInstallation =
        MapStyleInstallation.of(controller.addStyleLoadedListener(block))

    override fun upsertRaster(state: RasterLayerState) = controller.mountRasterLayer(state)

    override fun removeRaster(id: String) = controller.unmountRasterLayer(id)

    override fun report(diagnostics: List<String>) {
        if (diagnostics.isEmpty()) return
        val handler = diagnosticsHandler
        if (handler != null) {
            handler(diagnostics)
            return
        }
        // Never dropped. A rule that matched nothing, or an adjustment this
        // backend could not make, is the kind of thing that otherwise shows
        // up as "the style nearly worked" with no way to find out why.
        diagnostics.forEach { MapDiagnostics.sink.log("style: $it") }
    }
}

private const val CHURN_THRESHOLD = 5
