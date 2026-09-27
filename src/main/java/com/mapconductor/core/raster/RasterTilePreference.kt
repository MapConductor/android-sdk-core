package com.mapconductor.core.raster

import com.mapconductor.core.map.MapServiceKey

/**
 * どの大きさのタイルなら、この地図 SDK がうまく扱えるか。
 *
 * タイルの一辺は本来アプリの好みで決めてよく、既定は 512 にしてある（1 枚あたりの
 * 固定費 — HTTP の往復、PNG の符号化・復号、レンダラの立ち上げ — が枚数に比例する
 * ので、同じ画面を覆うなら枚数は少ないほうが安い）。ところが SDK 側に都合がある
 * ことがあり、その都合はレイヤを載せる側からは見えない。
 *
 * 実例が ArcGIS の 3D SceneView で、**タイルは 256px**という前提でレベルを選ぶ。
 * 512px のタイルを渡すと 1 段深いレベルを取りに行き、同じ画面を 4 倍の枚数で
 * 覆う（実測: Pixel 5a・統一ズーム 12 で、2D は z=11、3D は z=12）。絵は正しい
 * ので気づきにくいが、端末が遅いほど効く。
 *
 * そこでプロバイダに「この大きさで来てほしい」と言わせ、タイルを供給する側
 * （ベクタータイル層など）がそれを読む。アプリが明示した値のほうが常に強い。
 */
interface RasterTilePreference {
    /** この SDK に渡してほしいタイルの一辺（dp）。 */
    val preferredTileSize: Int
}

/**
 * [RasterTilePreference] の登録キー。
 *
 * 宣言しないプロバイダは「好みは無い」。その場合は供給側の既定が使われる。
 */
object RasterTilePreferenceKey : MapServiceKey<RasterTilePreference>
