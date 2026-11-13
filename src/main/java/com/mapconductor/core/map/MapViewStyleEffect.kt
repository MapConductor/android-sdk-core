package com.mapconductor.core.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.mapconductor.core.controller.BaseMapViewController

/**
 * Keeps the map's [MapViewStyle] installed, for every provider.
 *
 * Each `XxxMapView` takes a `style` and calls this once; nothing else about
 * a provider has to know what a vector style is. What a style then does to
 * the map depends on what that map declared it can do — draw a style
 * document, change a loaded one in place, or neither, in which case the
 * style's own rasteriser renders tiles and they go on as a raster layer.
 *
 * The decisions are all in [MapViewStyleHost.apply]; what this adds is
 * *when* to ask. Two of those are Compose's:
 *
 * - **On every composition, not only when `style` changes.** A style built
 *   inline is a new instance each time, and the one that matters — the one
 *   whose rules just moved — is indistinguishable from the one that did
 *   not. Only the host can tell, by its key, so it is asked every time.
 * - **Not before the controller exists.** A style acts on a live map, and
 *   the provider builds its controller asynchronously.
 *
 * ios-sdk-core and js-sdk-react do the same work; the shape differs because
 * each framework owns its lifecycle differently, and React Native does not
 * do it at all — there the style is carried to this SDK rather than
 * installed in JavaScript.
 */
@Composable
fun MapViewStyleEffect(
    state: MapViewStateInterface<*>,
    style: MapViewStyle?,
    controller: BaseMapViewController?,
    onDiagnostics: ((List<String>) -> Unit)? = null,
) {
    val report by rememberUpdatedState(onDiagnostics)
    val host = remember(state, controller) { controller?.let { MapViewStyleHost(state.serviceRegistry, it) } }

    // `SideEffect` rather than a call in the composition body: a
    // composition can be run more than once and thrown away, and installing
    // a style is not something to do speculatively. This runs once per
    // *successful* composition, with no keys, which is what "ask every
    // time" means. The host is what decides whether anything happens.
    SideEffect { host?.apply(style, report) }

    DisposableEffect(host) {
        onDispose { host?.dispose() }
    }
}
