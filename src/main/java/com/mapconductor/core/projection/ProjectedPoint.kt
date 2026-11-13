package com.mapconductor.core.projection

/**
 * 投影後の座標。**画面ピクセルではない。**
 *
 * 以前ここは `androidx.compose.ui.geometry.Offset` だった。Compose の `Offset` は
 * Float なので、画面座標（せいぜい数千）なら十分でも、Web Mercator のワールド
 * 座標には足りない。EPSG:3857 の x/y は ±20,037,508 m まで伸び、Float32 の有効
 * 桁 7 桁では小数部が丸ごと消える。
 *
 * 実測（`projection-vectors.txt`）:
 *
 * | 入力 | Double | Float |
 * | --- | --- | --- |
 * | 東京 | 15558805.184640 | 15558805.000000 |
 * | 経度 180 | 20037508.342789 | 20037508.000000 |
 *
 * 0.18〜0.34 m ずれる。ios-sdk は `CGPoint`、js-sdk-core は `number` で、どちらも
 * Double。**Android だけが落としていた。**
 *
 * 型を分けたのは、同じ理由で二度踏まないため。画面座標が欲しいところでは
 * `Offset` を使い続けてよい。
 */
data class ProjectedPoint(
    val x: Double,
    val y: Double,
)
