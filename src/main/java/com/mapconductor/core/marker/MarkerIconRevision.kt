package com.mapconductor.core.marker

import com.mapconductor.core.InternalMapConductorApi

/**
 * [MarkerState.icon] の URL の画像が届くたびに増える数。Compose から読むと、そのとき描き直される。
 *
 * URL の画像が届いても、アイコンは同じインスタンスのまま `hashCode` だけが変わるので、
 * `remember(marker.icon)` は作り直されない。マーカーをコンポーズのオーバーレイとして描く
 * プロバイダは、これも `remember` のキーにする。
 *
 * マーカーの値は 1 つの Compose 状態にまとめてあるので、これを読んでも状態の読み取りは
 * 増えない（タイルの描画スレッドやコントローラが 14 万回読むので、そこは増やさない）。
 */
@InternalMapConductorApi
val MarkerState.iconRevision: Int
    get() = iconRevisionValue
