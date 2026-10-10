package com.mapconductor.core.map

import androidx.compose.runtime.Stable
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.tileserver.LocalTileServer

/**
 * How a map looks, as a property of the map rather than a layer inside it.
 *
 * A vector style *is* the basemap, so passing one to `XxxMapView(style = ...)`
 * says what it is rather than mounting something over it. What happens
 * underneath depends on the backend and the app does not have to know:
 * MapLibre, Mapbox and MapTiler are told the style and draw it themselves;
 * Google Maps, MapKit, HERE, ArcGIS and the rest are handed raster tiles
 * rendered from the same style on the device. Either way the same
 * adjustments produce the same map.
 *
 * An interface rather than a class, for two reasons. Core must not depend on
 * the style compiler or the rasteriser — both are optional modules, and one
 * of them is a megabyte of native code — so what core holds is the seam and
 * the modules bring the implementation. And a provider's own styling, which
 * is nothing like a MapLibre style, can be added later as another
 * implementation without this changing.
 *
 * The concrete implementations live in the vector style module
 * (`com.mapconductor:vectorstyle`); an app does not implement this.
 *
 * ios-sdk-core and js-sdk-core carry the same seam.
 */
@Stable
interface MapViewStyle {
    /**
     * What identifies this style's content.
     *
     * The map re-installs a style whose key changed and leaves alone one
     * whose key did not. Two instances describing the same thing must have
     * the same key: a style built inline in a composable is a new instance
     * on every recomposition — which is exactly what a colour picker
     * produces — and keying off instance identity would reinstall the whole
     * style on every unrelated state change.
     */
    val key: String

    /**
     * Puts the style on the map. Called once the map has a style loaded,
     * and again if it loads another one.
     *
     * @return the ticket that takes it off again. Disposing must leave the
     *   map as it was found: a style that cannot be fully undone has no
     *   business being installed, because the app will switch away from it.
     */
    fun install(host: MapStyleHost): MapStyleInstallation
}

/**
 * A ticket to undo, and a chance to take a change without tearing down.
 *
 * Returned by anything that sets something up on a map.
 */
interface MapStyleInstallation {
    fun dispose()

    /**
     * Takes [next] in place, when it is near enough to what is installed.
     *
     * Without this the whole point is lost. A style whose key changed is
     * installed again, and installing a style means handing the map a
     * document -- which is the reload this design exists to avoid. Moving a
     * colour slider changes the adjustments and nothing else, so the
     * installed style answers true, applies the difference, and the map
     * never reloads anything.
     *
     * @return false when it cannot, and the caller disposes and installs
     *   [next] from scratch. False is always correct, only slower.
     */
    fun update(next: MapViewStyle): Boolean = false

    companion object {
        /** For a style that set nothing up. */
        @JvmField
        val NONE: MapStyleInstallation =
            object : MapStyleInstallation {
                override fun dispose() = Unit
            }

        /** The common case: one thing to undo, nothing that can be updated. */
        @JvmStatic
        fun of(dispose: () -> Unit): MapStyleInstallation =
            object : MapStyleInstallation {
                override fun dispose() = dispose()
            }
    }
}

/**
 * What a map offers to the style installed on it.
 *
 * The provider implements this; a [MapViewStyle] uses it and never touches
 * a provider type. That is what keeps one style implementation working on
 * every backend.
 */
interface MapStyleHost {
    /**
     * Where to look for what this map can do:
     * [VectorStyleSupportKey] to hand over a style document,
     * [VectorStyleMutationSupportKey] to change a loaded one in place,
     * [com.mapconductor.core.raster.RasterTilePreferenceKey] for the tile
     * size it would rather have.
     */
    val serviceRegistry: MapServiceRegistry

    /** For serving a style document, or tiles rendered from one, to the map. */
    val tileServer: LocalTileServer

    /**
     * The vector style the map is drawing now, when it is drawing one.
     *
     * Null on every backend that has no such thing, which is most of them.
     * It is how a rule set that adjusts "whatever is showing" finds
     * something to adjust: the style has to be read before a rule can be
     * matched against its layers, and only the map knows where it came from.
     */
    val vectorStyleUrl: String?
        get() = null

    /**
     * Runs [block] whenever the map finishes loading a style, including the
     * one it has now if it already has one.
     *
     * Everything a style installs on a live renderer is lost when that
     * renderer loads a document again, for any reason. This is how it gets
     * put back.
     */
    fun onStyleLoaded(block: () -> Unit): MapStyleInstallation

    /**
     * Adds or updates a raster layer the app did not declare.
     *
     * The raster path needs somewhere to put its tiles, and that cannot be
     * the content block: the style is a property of the view, so it is
     * installed from outside the composition. Marker tiling already mounts
     * its layer the same way.
     */
    fun upsertRaster(state: RasterLayerState)

    fun removeRaster(id: String)

    /**
     * Says what the app should know: a rule that matched nothing, an
     * adjustment this backend cannot make, a style that could not be read.
     *
     * Routed to whatever the host gave the style, so a module can report
     * without knowing how the app logs.
     */
    fun report(diagnostics: List<String>)
}
