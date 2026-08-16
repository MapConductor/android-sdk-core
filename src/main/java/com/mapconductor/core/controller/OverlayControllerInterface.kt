package com.mapconductor.core.controller

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPointInterface

interface OverlayControllerInterface<StateType, EntityType> {
    val zIndex: Int

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

/**
 * Capable ファサードのスロットに参加するオーバーレイコントローラ。
 *
 * [BaseMapViewController] の `compositionXxx` / `updateXxx` / `hasXxx` の既定実装は、
 * 登録済みコントローラのうちこれを実装しているものだけを [kind] で振り分ける。
 *
 * ## なぜ [OverlayControllerInterface] に既定つきで置かないか
 *
 * 既定値（`null`）を持たせると**宣言を忘れてもコンパイルが通り**、
 * `primaryOverlayController(kind)` が null を返して追加が黙って捨てられる。
 * 実際に AbstractMarkerController への宣言を忘れ、全プロバイダでマーカーが
 * 一切表示されない状態を作り込んだ。ビルドも apiCheck もユニットテストも緑のまま
 * すり抜けた（`null?.add(...)` は型として正しく、Kotlin の interface 既定プロパティは
 * 実装クラスにも getter を生成するのでリフレクションでも区別できない）。
 *
 * [kind] を抽象にして、宣言忘れをコンパイルエラーにする。
 * カメラ購読のためだけに登録する拡張モジュール（android-heatmap）は
 * これを実装しないので、スロットに巻き込まれない。
 */
@InternalMapConductorApi
interface SlottedOverlayController<StateType, EntityType> : OverlayControllerInterface<StateType, EntityType> {
    val kind: OverlayKind

    /**
     * タップの当たり判定と、当たったときの配送手段。当たらなければ null。
     *
     * クリックカスケード（[OverlayHitResolver]）の 1 段。解決するだけで配送はしない
     * （呼び出し側が [OverlayHit.dispatch] を呼ぶまで副作用は起きない）。
     *
     * ## これも [kind] と同じく**抽象**にしてある
     *
     * 既定で `null` を返せるようにすると、実装を忘れたコントローラが
     * 「タップに反応しないが、ビルドもテストも通る」状態になる。実際
     * android-for-maplibre / mapbox のポリゴンは [SlottedOverlayController] を
     * 実装し忘れていて、カスケードからも `hasPolygon` からも黙って漏れていた。
     *
     * クリックを持たない種別は明示的に `null` を返すこと（[com.mapconductor.core.raster.RasterLayerController]）。
     * マーカーは判定に画面投影が要るため別経路
     * （[BaseMapViewController.dispatchMarkerTap]）で、ここでは `null` を返す。
     */
    fun resolveTap(position: GeoPointInterface): OverlayHit?
}
