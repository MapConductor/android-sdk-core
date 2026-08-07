package com.mapconductor.core.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import kotlinx.coroutines.flow.StateFlow

enum class InitState {
    NotStarted,
    Initializing,
    SdkInitialized,
    MapViewCreated,
    MapCreating,
    MapCreated,
    MapLoaded,
    Failed,
}

interface MapViewStateInterface<ActualMapDesignType> {
    val id: String

    /**
     * 現在のカメラ。**カメラを読む正規の経路はここ**で、表示範囲は
     * `cameraPosition.visibleRegion?.bounds` から取る。
     *
     * プロバイダが地図 SDK のカメライベントごとに push する。変化を追いたい場合は
     * `onCameraMove` / `onCameraMoveEnd`、拡張モジュールは登録した
     * オーバーレイコントローラの `onCameraChanged` を使う。
     *
     * コントローラ側に getCameraPosition() / getBounds() を足さないこと。
     * 理由は [com.mapconductor.core.controller.MapViewControllerInterface] の
     * コメントと /docs/reading-camera を参照。
     */
    val cameraPosition: MapCameraPosition
    var mapDesignType: ActualMapDesignType

    /**
     * このマップにスコープされたサービス（プラグイン）のレジストリ。
     *
     * プロバイダが capability を登録し、拡張モジュール（marker-clustering など）が解決する。
     * これによりプロバイダはプラグインのインタフェースを実装せずに済み、プラグインは
     * どのプロバイダ上で動いているかを知らずに済む。
     *
     * **持ち主は state**。react-sdk の `MapViewState.serviceRegistry` /
     * ios-sdk の `MapViewState.serviceRegistry` と同じで、Compose 側は
     * [MapViewBase][com.mapconductor.compose.map.MapViewBase] が
     * [LocalMapServiceRegistry] へ供給する（react の `MapServiceRegistryProvider`、
     * ios の `MapServiceRegistryScope` に相当）。
     *
     * composable 側で `remember { MutableMapServiceRegistry() }` を作らないこと。
     * 地図インスタンスと寿命が一致する唯一のオブジェクトが state で、そこに置いておくと
     * Compose の外（React Native / Cordova ホストなど）からも同じ経路で登録できる。
     */
    val serviceRegistry: MutableMapServiceRegistry

    /** Which map gestures the user may perform. See [MapUISettings]. */
    var uiSettings: MapUISettings

    fun moveCameraTo(
        cameraPosition: MapCameraPosition,
        durationMillis: Long? = 0,
    )

    fun moveCameraTo(
        position: GeoPoint,
        durationMillis: Long? = 0,
    )

    fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int = 0,
    )

    fun getMapViewHolder(): MapViewHolderInterface<*, *>?
}

abstract class MapViewState<ActualMapDesignType> : MapViewStateInterface<ActualMapDesignType> {
    private val tag = this.javaClass.name

    /** @see MapViewStateInterface.serviceRegistry */
    override val serviceRegistry: MutableMapServiceRegistry = MutableMapServiceRegistry()

    // Backed by Compose state so flipping a gesture flag recomposes the map view.
    override var uiSettings: MapUISettings by mutableStateOf(MapUISettings.Default)
}

interface MapOverlayInterface<DataType> {
    val flow: StateFlow<MutableMap<String, DataType>>

    suspend fun render(
        data: MutableMap<String, DataType>,
        controller: MapViewControllerInterface,
    )
}

class MapOverlayRegistry {
    private val overlays = mutableListOf<MapOverlayInterface<*>>()

    fun register(overlay: MapOverlayInterface<*>) {
        if (overlays.toSet().contains(overlay)) return
        overlays.add(overlay)
    }

    fun getAll(): List<MapOverlayInterface<*>> = overlays.toList()
}
