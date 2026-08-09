package com.mapconductor.core.marker

import com.mapconductor.core.InternalMapConductorApi

interface MarkerEventControllerInterface<ActualMarker>

/**
 * ネイティブのマーカークリックを id からエンティティへ引き当てるための契約。
 *
 * MapConductor は原則としてクリックを地図クリックで受けてコアがヒットテストするが、
 * **Google Maps と TomTom のマーカーだけ** はネイティブがタップを必ず消費するため、
 * SDK のマーカークリックリスナーを経由せざるを得ない（`Marker` / `MarkerOptions` に
 * clickable 相当の API が無く、タップしても `OnMapClickListener` が発火しない。
 * polygon / polyline / circle には clickable があるので、そちらは地図クリックへ
 * 寄せてある）。この 2 プロバイダだけが実装する。
 */
@InternalMapConductorApi
interface NativeMarkerClickTargetInterface<ActualMarker> {
    /** state の id からエンティティを引く。未知の id なら null。 */
    fun getEntity(id: String): MarkerEntityInterface<ActualMarker>?

    /** クリックを配送する（`state.onClick` とコントローラのリスナーの両方へ）。 */
    fun dispatchClick(state: MarkerState)
}

/**
 * ネイティブのマーカークリックを、コアのクリック配送へ橋渡しする。
 *
 * android-for-googlemaps と android-for-tomtom が同じ判断を二重に持っていたものの集約。
 *
 * ## MapConductor 管理外のマーカーを素通しすること
 *
 * `mapViewState.getMapViewHolder().map` でネイティブの地図インスタンスをアプリへ
 * 公開しているため（アプリが既存ライブラリをそのまま使えるようにするための意図的な
 * 設計）、**アプリや他ライブラリが直接追加したマーカーが混ざりうる**。それらを
 * MapConductor が横取りしてはいけない。
 *
 * 判定は [tag] で行う。MapConductor が作ったマーカーには state の id（[String]）を
 * tag に入れてある。
 *
 *  - tag が [String] でない、または見覚えのない id → **false**（消費しない）。
 *    ネイティブの既定動作に委ねる。Google Maps ならこれが情報ウィンドウ表示＋
 *    カメラ移動にあたり、他ライブラリのマーカーにとって正しい挙動。
 *  - 見覚えのある id で `clickable` が false → **true**（消費するが配送しない）。
 *    既定動作も抑止したいので true を返す。
 *  - 見覚えのある id で `clickable` が true → 配送して **true**
 *
 * @param tag ネイティブのマーカーに設定された tag。
 * @return ネイティブへ返す「イベントを消費したか」。
 */
@InternalMapConductorApi
fun <ActualMarker> List<NativeMarkerClickTargetInterface<ActualMarker>>.dispatchNativeMarkerClick(tag: Any?): Boolean {
    // MapConductor 管理外のマーカー（アプリが直接 map へ追加したもの）は素通しする。
    val stateId = tag as? String ?: return false
    forEach { controller ->
        val entity = controller.getEntity(stateId) ?: return@forEach
        if (!entity.state.clickable) return true
        controller.dispatchClick(entity.state)
        return true
    }
    // どのコントローラも知らない id。MapConductor のマーカーではないので素通しする。
    return false
}
