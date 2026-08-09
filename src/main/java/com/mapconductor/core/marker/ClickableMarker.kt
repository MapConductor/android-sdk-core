package com.mapconductor.core.marker

/**
 * クリック配送のために、`clickable=false` のマーカーを「当たらなかった」ことにする。
 *
 * ## なぜヒットテストの中に入れないか
 *
 * マーカーのヒットテスト（[MarkerControllerInterface.find] やプロバイダ固有の
 * `find(position, zoom)`）は、**長押しドラッグの開始判定にも使われる**。そこで
 * `clickable` を見てしまうと `clickable=false` かつ `draggable=true` のマーカーが
 * ドラッグ不能になる。よって判定はクリック経路にだけ被せる。
 *
 * ## なぜカスケードの手前で弾くのか
 *
 * `clickable=false` は **透過**（そのオーバーレイはタップを受け取らず、下の
 * オーバーレイ、無ければ地図クリックへイベントが流れる）。ヒットした後で配送だけ
 * 止めると、カスケードはそこで止まるのにイベントは配送されず **握り潰し** になる。
 * circle / polygon / polyline / groundImage は各 Manager の `find` が
 * `clickable=false` を除外して透過を実現しており、マーカーもこれを通すことで
 * 同じ意味論になる。
 *
 * ## 使い方
 *
 * クリックのカスケードでは、どの find 変種を使う場合でも必ずこれを通すこと。
 *
 * ```kotlin
 * controller.find(touchPosition).clickableOnly()?.let { entity ->
 *     controller.dispatchClick(entity.state)
 *     return true
 * }
 * // 当たらなければ次の層へ
 * ```
 *
 * react-sdk の `clickableOnly()` と同じ契約。
 */
fun <ActualMarker> MarkerEntityInterface<ActualMarker>?.clickableOnly(): MarkerEntityInterface<ActualMarker>? =
    this?.takeIf { it.state.clickable }
