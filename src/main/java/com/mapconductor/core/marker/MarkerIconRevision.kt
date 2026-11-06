package com.mapconductor.core.marker

import com.mapconductor.core.InternalMapConductorApi

/**
 * [MarkerState.icon] の URL の画像が届くたびに増える数。Compose から読むと、そのとき描き直される。
 *
 * URL の画像が届いても、アイコンは同じインスタンスのまま `hashCode` だけが変わるので、
 * `marker.icon` を読んでいるだけのコンポジションは走り直さない。マーカーをコンポーズの
 * オーバーレイとして描くプロバイダは、これも読んで `remember` のキーにする。
 *
 * [MarkerState.icon] の読み出し自体は Compose の状態を増やさない。タイルの描画スレッドや
 * コントローラが 14 万回読むので、そこへ余計な読み取りを足さないため。
 */
@InternalMapConductorApi
val MarkerState.iconRevision: Int
    get() = iconRevisionState.intValue
