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

    /**
     * この SDK がラスタータイルを **物理ピクセル** でレイアウトするなら true。
     *
     * 地図 SDK には 2 つの流儀がある。宣言された一辺を dp として敷くものと、
     * 物理ピクセルとして敷くもの。後者に density 2 の端末で「512」と宣言すると
     * 256 dp しか覆わず、1 段深いズームを要求してスタイルをそこで読む —
     * ラベルは半分、道路は倍の密度になる（web の HERE で実測）。供給側は
     * この流儀を知りようがないので、プロバイダが言う。
     *
     * true のとき、供給側は [preferredTileSize] × density を宣言し、描画は
     * 同じピクセル数で行い、スタイルには [preferredTileSize] dp として
     * 読ませる。既定は false。react-sdk / ios-sdk の同名フィールドに対応。
     */
    val layoutInDevicePixels: Boolean
        get() = false
}

/**
 * [RasterTilePreference] の登録キー。
 *
 * 宣言しないプロバイダは「好みは無い」。その場合は供給側の既定が使われる。
 */
object RasterTilePreferenceKey : MapServiceKey<RasterTilePreference>
