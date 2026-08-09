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

/**
 * 全プロバイダ共通の state 実装。
 *
 * カメラの保持と、コントローラへの委譲（[moveCameraTo] / [fitBounds] /
 * [getMapViewHolder]）はどのプロバイダでも同じなのでここに置く。プロバイダ固有なのは
 * `mapDesignType` の型と、`getMapViewHolder` の戻り型を絞る共変オーバーライドだけ。
 *
 * @param initialCameraPosition コントローラが繋がるまでの間、保持しておくカメラ。
 *   [attachController] の時点でこの位置へ移動する（[attachController] の第2引数で抑止可）。
 * @param optimisticCameraUpdate [moveCameraTo] で、コントローラへ委譲する**前に**
 *   要求されたカメラを [cameraPosition] へ反映するか。
 *
 *   既定は `false`。地図のカメライベントが返ってきてから
 *   [setCameraPositionInternal] で反映する（ネイティブ SDK は確実にイベントを返すので、
 *   実際に適用された値だけが state に入る）。
 *
 *   `true` にするのは WebView ブリッジ越しのプロバイダ（MapTiler / Longdo）。
 *   イベントの往復が遅く、要求直後に [cameraPosition] を読むと古い値が返ってしまうため。
 */
abstract class MapViewState<ActualMapDesignType>(
    initialCameraPosition: MapCameraPosition = MapCameraPosition.Default,
    private val optimisticCameraUpdate: Boolean = false,
) : MapViewStateInterface<ActualMapDesignType> {
    /** @see MapViewStateInterface.serviceRegistry */
    override val serviceRegistry: MutableMapServiceRegistry = MutableMapServiceRegistry()

    // Backed by Compose state so flipping a gesture flag recomposes the map view.
    override var uiSettings: MapUISettings by mutableStateOf(MapUISettings.Default)

    /**
     * 接続済みのコントローラ。まだ地図が生成されていなければ null。
     *
     * 名前が `controller` でないのは、多くのプロバイダが自分のコントローラ型で
     * `controller` フィールドを持っており、衝突させないため。プロバイダは
     * 自前のフィールドを持ったまま [attachController] を呼べばよい。
     */
    protected var attachedMapController: MapViewControllerInterface? = null
        private set

    // Compose state で持つ。composable が state.cameraPosition を読んでいる場合、
    // カメラが動いたら再コンポーズされる必要がある。
    private var _cameraPosition: MapCameraPosition by mutableStateOf(initialCameraPosition)

    override val cameraPosition: MapCameraPosition
        get() = _cameraPosition

    /**
     * コントローラを接続する。プロバイダの `setController` から呼ぶ。
     *
     * @param moveToInitialCamera 接続時に、保持していたカメラ位置へ移動するか。
     *   既定は `true`。地図の生成直後にカメラを動かすと初期位置が上書きされてしまう
     *   プロバイダ（ArcGIS は Scene のロード中に viewpointChanged が zoom~0 で
     *   発火する。MapTiler / Longdo は WebView の ready 後に別経路で適用する）は
     *   `false` を渡す。
     */
    protected fun attachController(
        controller: MapViewControllerInterface,
        moveToInitialCamera: Boolean = true,
    ) {
        this.attachedMapController = controller
        if (moveToInitialCamera) {
            controller.moveCamera(_cameraPosition)
        }
    }

    /** コントローラを切り離す（地図の破棄時など）。 */
    protected fun detachController() {
        this.attachedMapController = null
    }

    /**
     * 地図から通知された現在のカメラを保持する（地図を動かさない）。
     *
     * プロバイダの `updateCameraPosition` から呼ぶ。カメラを**動かしたい**ときは
     * [moveCameraTo] を使うこと。
     */
    protected fun setCameraPositionInternal(cameraPosition: MapCameraPosition) {
        _cameraPosition = cameraPosition
    }

    override fun moveCameraTo(
        position: GeoPoint,
        durationMillis: Long?,
    ) {
        moveCameraTo(_cameraPosition.copy(position = position), durationMillis)
    }

    override fun moveCameraTo(
        cameraPosition: MapCameraPosition,
        durationMillis: Long?,
    ) {
        val ctrl = attachedMapController
        if (ctrl == null) {
            // まだ地図が無い。接続時に attachController がこの位置へ移動する。
            _cameraPosition = cameraPosition
            return
        }
        if (optimisticCameraUpdate) {
            _cameraPosition = cameraPosition
        }
        // 引数は既に MapCameraPosition なので、プロバイダごとの
        // `MapCameraPosition.Companion.from` は恒等変換になる（どの実装も
        // `is MapCameraPosition -> position` で素通しする）。ここでは呼ばない。
        if (durationMillis == null || durationMillis == 0L) {
            ctrl.moveCamera(cameraPosition)
        } else {
            ctrl.animateCamera(cameraPosition, durationMillis)
        }
    }

    override fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    ) {
        attachedMapController?.fitBounds(bounds, padding)
    }

    /**
     * 各プロバイダは戻り型を自分のホルダー型へ絞る共変オーバーライドを 1 行だけ置くこと。
     * アプリが `state.getMapViewHolder()?.map` でネイティブの地図を取れる形を保つため、
     * ここを継承で済ませてはいけない（静的型が広がってソース非互換になる）。
     */
    override fun getMapViewHolder(): MapViewHolderInterface<*, *>? = attachedMapController?.holder
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
