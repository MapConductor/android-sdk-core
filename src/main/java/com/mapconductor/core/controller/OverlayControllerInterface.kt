package com.mapconductor.core.controller

import com.mapconductor.core.features.GeoPointInterface

interface OverlayControllerInterface<StateType, EntityType> {
    val zIndex: Int

    /**
     * 種別。[BaseMapViewController] の Capable 既定実装が振り分けに使う。
     *
     * 既定は `null`＝どのスロットにも入らない。拡張モジュールが独自に登録する
     * コントローラ（android-heatmap のカメラ購読用など）を巻き込まないため。
     */
    val kind: OverlayKind? get() = null

    suspend fun add(data: List<StateType>)

    suspend fun update(state: StateType)

    suspend fun clear()

    fun find(position: GeoPointInterface): EntityType?

    /**
     * この id のオーバーレイを保持しているか。
     *
     * [com.mapconductor.core.controller.BaseMapViewController] が `hasXxx` の既定実装で使う。
     * 既定は `false`（拡張モジュールが独自に実装する OverlayController を壊さないため）。
     */
    fun has(id: String): Boolean = false

    /**
     * 非推奨の `setOnXxxClickListener` から呼ばれる、型を消したクリックリスナー設定。
     *
     * [BaseMapViewController] の既定実装が使う。型付きの `clickListener` を持つ
     * コアのコントローラだけが実装する。既定は何もしない。
     */
    fun setClickListenerAny(listener: Any?) {}

    /**
     * Cleanup resources when the controller is no longer needed.
     * IMPORTANT: Call this when switching map providers or disposing the map.
     */
    fun destroy()
}
