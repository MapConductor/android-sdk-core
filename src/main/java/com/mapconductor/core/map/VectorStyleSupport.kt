package com.mapconductor.core.map

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * A map that can draw a MapLibre style itself.
 *
 * MapLibre, Mapbox and MapTiler are vector renderers already: handing them a
 * style document is cheaper than rasterising it on the device and feeding the
 * result back as PNG tiles, which is what a backend that only takes raster
 * (Google Maps, ArcGIS, HERE...) has to be given. A provider that can take
 * the style directly registers this under [VectorStyleSupportKey]; a layer
 * that has a style to show looks the key up and, if it is there, skips the
 * rasteriser altogether.
 *
 * The style *becomes the basemap*: on every provider that can do this, the
 * style is the map, so showing one replaces the design the state names and
 * clearing it puts that design back. A style drawn this way is not an
 * overlay -- it has no opacity, and nothing of the previous basemap shows
 * through it. Anything that needs the style on top of another basemap goes
 * through the raster path.
 *
 * ios-sdk-core and js-sdk-core carry the same capability.
 */
interface VectorStyleSupport {
    /**
     * Draws [styleUrl] as the map's basemap.
     *
     * [attributionRules] are the credits the style's sources ask for, carried
     * by the design so the map's attribution overlay shows them for as long
     * as the style is up.
     */
    fun showStyle(
        styleUrl: String,
        attributionRules: List<AttributionRule> = emptyList(),
    )

    /**
     * Restores the design that was showing before [showStyle], unless the app
     * has since chosen another one, in which case that choice is left alone.
     */
    fun clearStyle()

    /**
     * Where the style the map is drawing came from, when that is knowable.
     *
     * This is what lets a rule set adjust *whatever is showing* rather than a
     * style the app had to name: the document has to be read before a rule
     * can be matched against its layers, and only the provider knows where
     * it came from.
     *
     * Null when it is not a URL anything can fetch -- a provider's own named
     * design (`mapbox://styles/mapbox/standard`), a style that needs a key
     * this does not have. A caller that wanted it says so and leaves the map
     * alone rather than showing something unstyled.
     */
    val currentStyleUrl: String?
        get() = null
}

/**
 * [VectorStyleSupport] の登録キー。
 *
 * 宣言しないプロバイダは「スタイルは受け取れない」。ベクタータイル層はその場合
 * ラスタータイルに描いて渡す。
 */
object VectorStyleSupportKey : MapServiceKey<VectorStyleSupport>

/**
 * [VectorStyleSupport] for a provider whose design type can name a style URL.
 *
 * The three MapLibre-based providers differ only in how a URL is wrapped
 * into their design type, which is what [designFor] supplies; the
 * remember-and-restore dance is the same for all of them and lives here
 * once. The "still ours" check on [clearStyle] is what keeps this from
 * fighting an app that switched designs while the style was up: a layer
 * unmounting must not undo a choice the app made after it.
 *
 * [clearStyle] takes effect a moment later rather than at once, and a
 * [showStyle] of the same URL in between cancels it. A provider that
 * rebuilds its whole view on a design change (MapTiler keys the view on the
 * design id) also rebuilds the overlay content inside it, so the layer that
 * asked for the style is unmounted and mounted again *by the change it
 * caused*. Clearing on that unmount would restore the old design, which
 * would rebuild the view again, and so on without end -- the map never
 * finishes coming up. Waiting one frame lets the remount cancel the clear.
 */
class VectorStyleAsDesign<Design : MapDesignTypeInterface<*>>(
    private val state: MapViewStateInterface<Design>,
    private val urlOf: ((Design) -> String?)? = null,
    private val designFor: (styleUrl: String, attributionRules: List<AttributionRule>) -> Design,
) : VectorStyleSupport {
    private val main = Handler(Looper.getMainLooper())
    private var installed: Design? = null
    private var installedUrl: String? = null
    private var previous: Design? = null
    private var pendingClear: Runnable? = null

    override fun showStyle(
        styleUrl: String,
        attributionRules: List<AttributionRule>,
    ) {
        pendingClear?.let { main.removeCallbacks(it) }
        pendingClear = null
        val current = installed
        // Same style, still up: nothing to do, and writing the design again
        // would rebuild the view for nothing.
        if (current != null && installedUrl == styleUrl && state.mapDesignType.id == current.id) return
        val design = designFor(styleUrl, attributionRules)
        Log.d(TAG, "showStyle $styleUrl (was ${state.mapDesignType.id})")
        // Showing a second style over the first keeps the *original* design
        // as the one to go back to; the intermediate style was never the
        // app's basemap.
        if (current == null) previous = state.mapDesignType
        installed = design
        installedUrl = styleUrl
        state.mapDesignType = design
    }

    override fun clearStyle() {
        if (installed == null) return
        pendingClear?.let { main.removeCallbacks(it) }
        val clear =
            Runnable {
                pendingClear = null
                val current = installed ?: return@Runnable
                val restore = previous
                installed = null
                installedUrl = null
                previous = null
                if (state.mapDesignType.id == current.id && restore != null) {
                    Log.d(TAG, "clearStyle: restoring ${restore.id}")
                    state.mapDesignType = restore
                }
            }
        pendingClear = clear
        main.postDelayed(clear, CLEAR_GRACE_MS)
    }

    /**
     * Where the design **the app chose** points, when it points anywhere
     * fetchable.
     *
     * Deliberately not the URL this capability installed. That one is the
     * document the style module served a moment ago, and it is unregistered
     * from the tile server the instant the style comes off -- so answering
     * with it turns "adjust whatever is showing" into a 404 against our own
     * corpse. When one of ours is up, the honest answer is the design it
     * replaced.
     *
     * Null unless the provider supplied [urlOf], and null from that for a
     * design that is not a plain style document -- a provider's own named
     * basemap, or one that needs a key this does not have.
     */
    override val currentStyleUrl: String?
        get() {
            val design = if (installed != null) previous else state.mapDesignType
            return design?.let { urlOf?.invoke(it) }
        }

    private companion object {
        /** Longer than a frame, shorter than anyone notices. */
        const val CLEAR_GRACE_MS = 100L
        private const val TAG = "VectorStyle"
    }
}
