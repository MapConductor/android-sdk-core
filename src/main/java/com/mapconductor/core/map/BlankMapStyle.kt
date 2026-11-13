package com.mapconductor.core.map

/**
 * A MapLibre-style map with nothing in it but a background.
 *
 * What a provider shows for "no basemap" when it is built on MapLibre or takes
 * a MapLibre style (Mapbox, MapTiler, TomTom). A map that draws its whole
 * viewport itself -- vector tiles rendered on the device, say -- fetches and
 * paints the basemap under it for nobody; this is the style that fetches
 * nothing. ios-sdk-core and js-sdk-core carry the same style.
 *
 * The same style is bundled as an asset (`mapconductor/blank-style.json`,
 * merged into the app from this library) for SDKs that only take a URI.
 */
object BlankMapStyle {
    /** このスタイルが持つ唯一の色。 */
    const val BACKGROUND_COLOR: String = "#f2efe9"

    const val JSON: String =
        """{"version":8,"name":"blank","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#f2efe9"}}]}"""

    /** The bundled asset, as the `asset://` URI MapLibre-based SDKs read. */
    const val ASSET_URI: String = "asset://mapconductor/blank-style.json"

    /** The bundled asset, as the `file:` URL a WebView-based SDK reads. */
    const val ASSET_FILE_URL: String = "file:///android_asset/mapconductor/blank-style.json"
}
