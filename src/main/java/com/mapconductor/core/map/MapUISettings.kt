package com.mapconductor.core.map

/**
 * Which map gestures the user is allowed to perform.
 *
 * Mirrors `MapUISettings` on iOS and React. Every flag defaults to `true`, so the
 * default value leaves the provider's own behaviour untouched.
 *
 * Not every provider can honour every flag — some map SDKs bundle two gestures
 * into one recogniser. Setting an unsupported flag to `false` is ignored and logs
 * a one-time warning; see [MapGesture] and [MapUISettingsDiagnostics].
 */
data class MapUISettings(
    /** Pan / drag the map. */
    val scrollGesture: Boolean = true,
    /** Pinch and double-tap zoom. */
    val zoomGesture: Boolean = true,
    /** Rotate the map (change bearing). */
    val rotateGesture: Boolean = true,
    /** Tilt the map (change pitch). */
    val tiltGesture: Boolean = true,
) {
    companion object {
        /** All gestures enabled — the default. */
        val Default = MapUISettings()

        /** Every gesture disabled; the map becomes non-interactive. */
        val None =
            MapUISettings(
                scrollGesture = false,
                zoomGesture = false,
                rotateGesture = false,
                tiltGesture = false,
            )
    }
}

/** The gestures [MapUISettings] can turn on and off. */
enum class MapGesture(
    val settingName: String,
) {
    Scroll("scrollGesture"),
    Zoom("zoomGesture"),
    Rotate("rotateGesture"),
    Tilt("tiltGesture"),
}

/**
 * Reports gesture flags a provider cannot honour.
 *
 * Providers call [warnIfRequested] when a flag is set to `false` that their map
 * engine has no way to disable. Warnings are logged once per provider+gesture so
 * a composable that recomposes on every camera move does not flood logcat.
 */
object MapUISettingsDiagnostics {
    /**
     * Logs once if [requested] is `false` — i.e. the app asked to disable a
     * gesture this provider cannot disable. A `true` value needs no warning,
     * because leaving a gesture enabled is always achievable.
     *
     * 実体は [MapDiagnostics] に一般化済み。ジェスチャは「無効化を要求されたのに
     * できない」という向きなので、`requested = !requested` として渡している。
     */
    fun warnIfRequested(
        requested: Boolean,
        gesture: MapGesture,
        provider: String,
        reason: String,
    ) {
        MapDiagnostics.reportIfRequested(
            requested = !requested,
            capability = gesture.capability,
            level = MapDiagnosticLevel.Ignored,
            provider = provider,
            reason = reason,
            subject = gesture.settingName,
        )
    }

    /** Test hook — forget which warnings have already been logged. */
    fun resetWarnings() {
        MapDiagnostics.resetWarnings()
    }
}
