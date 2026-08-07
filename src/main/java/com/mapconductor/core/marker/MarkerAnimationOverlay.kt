package com.mapconductor.core.marker

/**
 * A marker animation delegated to the Compose "marker animation layer".
 *
 * Instead of interpolating geographic coordinates (which produces wrong
 * directions when the map is tilted, rotated, or rendered as a globe), the
 * layer animates the marker's image in screen space above the map view and
 * calls [onFinished] when done so the native marker can be shown at its
 * final position.
 */
data class MarkerAnimationOverlayEntry(
    val id: String,
    val state: MarkerState,
    val icon: BitmapIcon,
    val animation: MarkerAnimation,
    val durationMillis: Long,
    val onFinished: () -> Unit,
)

/**
 * Implemented by the Compose layer (MapViewBase) and handed to renderers via
 * `MarkerCapableInterface.setMarkerAnimationOverlayHost`. Renderers that set
 * `supportsAnimationOverlay = true` delegate their animations here.
 */
fun interface MarkerAnimationOverlayHost {
    fun start(entry: MarkerAnimationOverlayEntry)
}
