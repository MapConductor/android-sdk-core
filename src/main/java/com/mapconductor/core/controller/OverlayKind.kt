package com.mapconductor.core.controller

/**
 * オーバーレイの種別。
 *
 * [BaseMapViewController] が Capable ファサード（`compositionXxx` / `updateXxx` /
 * `hasXxx`）の既定実装で、登録済みの [OverlayControllerInterface] を振り分けるのに使う。
 *
 * ジェネリクスは実行時に消えるので状態の型では判別できない。かといって `Class` を
 * 持たせると、状態型がプロバイダ固有になったときに破綻する。種別という別の軸で持つ。
 *
 * **描画順（zIndex）とは別軸**であることに注意。現状 polygon(3) > groundImage(2) だが、
 * クリックの探索は groundImage が先。
 */
enum class OverlayKind {
    Marker,
    Circle,
    GroundImage,
    Polyline,
    Polygon,
    RasterLayer,
}
