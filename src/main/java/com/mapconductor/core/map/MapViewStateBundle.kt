package com.mapconductor.core.map

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import android.os.Bundle

/**
 * `rememberSaveable` の Saver（[BaseMapViewSaver] とプロバイダごとの `*MapViewSaver`）が
 * state を [Bundle] へ書き出し、読み戻すための共通部品。
 *
 * ## なぜ要るか
 *
 * 画面回転などの構成変更で Activity が作り直されると、state は Saver が [Bundle] に
 * 書いたものからだけ作り直される。書かなかった値は**黙って既定値に戻る**
 * （デザインの attributionRules が消える、uiSettings が全部 true に戻る、など）。
 * どのプロバイダも同じ値を同じ形で書けるよう、ここにまとめる。
 *
 * ## 値の形
 *
 * [Bundle] にはプリミティブと入れ子の [Bundle] だけを入れる。`Serializable` を使わない
 * のは、プロセスが落ちたあとの復元でもクラスローダーに依存せず読めるようにするため。
 * リストは「件数 + 添字をキーにした子 Bundle」で表す。
 */
@InternalMapConductorApi
object MapViewStateBundle {
    /** プロバイダの design Bundle で attributionRules を入れるキー。 */
    const val KEY_ATTRIBUTION_RULES: String = "attributionRules"

    /** [BaseMapViewSaver] が uiSettings を入れるキー。 */
    const val KEY_UI_SETTINGS: String = "uiSettings"

    // ---- AttributionRule -------------------------------------------------

    fun putAttributionRules(
        bundle: Bundle,
        rules: List<AttributionRule>,
        key: String = KEY_ATTRIBUTION_RULES,
    ) {
        bundle.putBundle(key, attributionRulesToBundle(rules))
    }

    /** 保存されていなければ空リスト。 */
    fun getAttributionRules(
        bundle: Bundle?,
        key: String = KEY_ATTRIBUTION_RULES,
    ): List<AttributionRule> = attributionRulesFromBundle(bundle?.getBundle(key))

    fun attributionRulesToBundle(rules: List<AttributionRule>): Bundle =
        Bundle().apply {
            putInt(KEY_COUNT, rules.size)
            rules.forEachIndexed { index, rule ->
                putBundle(index.toString(), attributionRuleToBundle(rule))
            }
        }

    fun attributionRulesFromBundle(bundle: Bundle?): List<AttributionRule> {
        if (bundle == null) return emptyList()
        val count = bundle.getInt(KEY_COUNT, 0)
        return (0 until count).mapNotNull { index ->
            attributionRuleFromBundle(bundle.getBundle(index.toString()))
        }
    }

    private fun attributionRuleToBundle(rule: AttributionRule): Bundle =
        Bundle().apply {
            putString("attribution", rule.attribution)
            rule.minZoom?.let { putInt("minZoom", it) }
            rule.maxZoom?.let { putInt("maxZoom", it) }
            rule.bounds?.let { putBundle("bounds", boundsToBundle(it)) }
        }

    private fun attributionRuleFromBundle(bundle: Bundle?): AttributionRule? {
        val attribution = bundle?.getString("attribution") ?: return null
        return AttributionRule(
            attribution = attribution,
            minZoom = if (bundle.containsKey("minZoom")) bundle.getInt("minZoom") else null,
            maxZoom = if (bundle.containsKey("maxZoom")) bundle.getInt("maxZoom") else null,
            bounds = bundle.getBundle("bounds")?.let(::boundsFromBundle),
        )
    }

    private fun boundsToBundle(bounds: GeoRectBounds): Bundle =
        Bundle().apply {
            bounds.southWest?.let { putBundle("southWest", pointToBundle(it)) }
            bounds.northEast?.let { putBundle("northEast", pointToBundle(it)) }
        }

    private fun boundsFromBundle(bundle: Bundle): GeoRectBounds =
        GeoRectBounds(
            southWest = bundle.getBundle("southWest")?.let(::pointFromBundle),
            northEast = bundle.getBundle("northEast")?.let(::pointFromBundle),
        )

    private fun pointToBundle(point: GeoPoint): Bundle =
        Bundle().apply {
            putDouble("latitude", point.latitude)
            putDouble("longitude", point.longitude)
            putDouble("altitude", point.altitude)
        }

    private fun pointFromBundle(bundle: Bundle): GeoPoint =
        GeoPoint(
            latitude = bundle.getDouble("latitude"),
            longitude = bundle.getDouble("longitude"),
            altitude = bundle.getDouble("altitude"),
        )

    // ---- MapPaddings ------------------------------------------------------

    fun paddingsToBundle(paddings: MapPaddingsInterface): Bundle =
        Bundle().apply {
            putDouble("top", paddings.top)
            putDouble("left", paddings.left)
            putDouble("bottom", paddings.bottom)
            putDouble("right", paddings.right)
        }

    fun paddingsFromBundle(bundle: Bundle?): MapPaddings? {
        if (bundle == null) return null
        return MapPaddings(
            top = bundle.getDouble("top"),
            left = bundle.getDouble("left"),
            bottom = bundle.getDouble("bottom"),
            right = bundle.getDouble("right"),
        )
    }

    // ---- MapUISettings ----------------------------------------------------

    fun uiSettingsToBundle(settings: MapUISettings): Bundle =
        Bundle().apply {
            putBoolean("scrollGesture", settings.scrollGesture)
            putBoolean("zoomGesture", settings.zoomGesture)
            putBoolean("rotateGesture", settings.rotateGesture)
            putBoolean("tiltGesture", settings.tiltGesture)
            putBundle("paddings", paddingsToBundle(settings.paddings))
        }

    /** 保存されていなければ null（呼び出し側は state の既定値をそのまま使う）。 */
    fun uiSettingsFromBundle(bundle: Bundle?): MapUISettings? {
        if (bundle == null) return null
        return MapUISettings(
            scrollGesture = bundle.getBoolean("scrollGesture", true),
            zoomGesture = bundle.getBoolean("zoomGesture", true),
            rotateGesture = bundle.getBoolean("rotateGesture", true),
            tiltGesture = bundle.getBoolean("tiltGesture", true),
            paddings = paddingsFromBundle(bundle.getBundle("paddings")) ?: MapPaddings.Zeros,
        )
    }

    // ---- MapCameraPosition -----------------------------------------------

    /**
     * カメラを書き出す。キー（zoom / tilt / bearing / latitude / longitude）は
     * 以前の [BaseMapViewSaver] と同じなので、古い Bundle もそのまま読める。
     * `visibleRegion` は地図が描画のたびに計算し直すので保存しない。
     */
    fun cameraToBundle(camera: MapCameraPositionInterface): Bundle =
        Bundle().apply {
            putDouble("zoom", camera.zoom)
            putDouble("tilt", camera.tilt)
            putDouble("bearing", camera.bearing)
            putDouble("latitude", camera.position.latitude)
            putDouble("longitude", camera.position.longitude)
            putDouble("altitude", camera.position.altitude ?: 0.0)
            camera.paddings?.let { putBundle("paddings", paddingsToBundle(it)) }
        }

    /**
     * @param fallbackPaddings paddings が保存されていなかったとき（以前の形式の Bundle）に使う値。
     */
    fun cameraFromBundle(
        bundle: Bundle?,
        fallbackPaddings: MapPaddingsInterface? = null,
    ): MapCameraPosition =
        MapCameraPosition(
            position =
                GeoPoint(
                    latitude = bundle?.getDouble("latitude") ?: 0.0,
                    longitude = bundle?.getDouble("longitude") ?: 0.0,
                    altitude = bundle?.getDouble("altitude") ?: 0.0,
                ),
            zoom = bundle?.getDouble("zoom") ?: 0.0,
            bearing = bundle?.getDouble("bearing") ?: 0.0,
            tilt = bundle?.getDouble("tilt") ?: 0.0,
            paddings = paddingsFromBundle(bundle?.getBundle("paddings")) ?: fallbackPaddings,
        )

    private const val KEY_COUNT = "count"
}
