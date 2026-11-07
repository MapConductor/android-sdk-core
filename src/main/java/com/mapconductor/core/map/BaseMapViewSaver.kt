package com.mapconductor.core.map

import androidx.compose.runtime.saveable.Saver
import com.mapconductor.core.InternalMapConductorApi
import android.os.Bundle

/**
 * Base class for MapView state savers
 *
 * カメラ（paddings を含む）と [MapViewStateInterface.uiSettings] はここで保存・復元する。
 * デザインは型がプロバイダごとに違うので [saveMapDesign] / [createState] が受け持つ。
 * デザインの `attributionRules` を含め、アプリが設定したものは全部書くこと
 * （`MapViewStateBundle.putAttributionRules` を使う）。書かなかった値は回転などの
 * 構成変更のあと黙って既定値に戻る。
 *
 * @param T MapViewStateInterface type
 */
@OptIn(InternalMapConductorApi::class)
abstract class BaseMapViewSaver<T : MapViewStateInterface<*>> {
    /**
     * Save map design type to bundle
     */
    protected abstract fun saveMapDesign(
        state: T,
        bundle: Bundle,
    )

    /**
     * Create state instance from restored data
     */
    protected abstract fun createState(
        stateId: String,
        mapDesignBundle: Bundle?,
        cameraPosition: MapCameraPosition,
    ): T

    /**
     * Get paddings for restored camera position (can be overridden by subclasses)
     */
    protected open fun getCameraPaddings(): MapPaddingsInterface? = null

    /**
     * Create the actual Saver instance
     */
    fun createSaver(): Saver<T, Bundle> =
        Saver(
            save = { state ->
                val cameraStateBundle = createCameraBundle(state)
                val mapDesignBundle =
                    Bundle().apply {
                        saveMapDesign(state, this)
                    }

                Bundle().apply {
                    putString("stateId", getStateId(state))
                    putBundle("mapDesign", mapDesignBundle)
                    putBundle("camera", cameraStateBundle)
                    putBundle(MapViewStateBundle.KEY_UI_SETTINGS, MapViewStateBundle.uiSettingsToBundle(state.uiSettings))
                }
            },
            restore = { storedData ->
                val cameraBundle = storedData.getBundle("camera")
                val mapDesignBundle = storedData.getBundle("mapDesign")
                val stateId = storedData.getString("stateId")!!

                val cameraPosition = createCameraPositionFromBundle(cameraBundle)

                createState(stateId, mapDesignBundle, cameraPosition).also { state ->
                    // uiSettings はどのプロバイダでも MapViewStateInterface の var なので、
                    // createState に渡さずここで戻す（以前は保存しておらず、復元後は
                    // 全部 true の既定値に戻っていた）。
                    MapViewStateBundle.uiSettingsFromBundle(storedData.getBundle(MapViewStateBundle.KEY_UI_SETTINGS))?.let {
                        state.uiSettings = it
                    }
                }
            },
        )

    /**
     * Extract state ID from the state object
     */
    protected abstract fun getStateId(state: T): String

    private fun createCameraBundle(state: T): Bundle = MapViewStateBundle.cameraToBundle(state.cameraPosition)

    private fun createCameraPositionFromBundle(cameraBundle: Bundle?): MapCameraPosition =
        MapViewStateBundle.cameraFromBundle(cameraBundle, fallbackPaddings = getCameraPaddings())
}
