package com.mapconductor.core.map

/**
 * プロバイダが対応できる機能の識別子。
 *
 * 型付きの [MapServiceKey] が「どう実装するか」を運ぶのに対し、こちらは
 * 「対応しているか」だけを表す安定した ID。アプリ開発者に型キーを触らせずに
 * 対応状況を問い合わせられるようにするために分けてある。
 *
 * 値を持つ capability（例: 穴を何個描けるか）は型付きキーで別途登録すること。
 * ここは真偽ではなく [MapCapabilityStatus] の 4 段階で表現する。
 *
 * ios-sdk / react-sdk にも同名・同じ [id] で置く。[id] は永続化やログに出るため、
 * enum の名前を変えても [id] は変えないこと。
 */
enum class MapCapability(
    val id: String,
) {
    // オーバーレイ
    Marker("marker"),
    Polyline("polyline"),
    Polygon("polygon"),
    Circle("circle"),
    GroundImage("groundImage"),
    RasterLayer("rasterLayer"),

    /** 穴付きポリゴンを描けるか。何個描けるかは別途 [MapServiceKey] で値を登録する。 */
    PolygonHoles("polygonHoles"),

    /**
     * オーバーレイをタップに対して透過させられるか。
     *
     * MapConductor は原則としてクリックを地図クリックで受け、コアがヒットテストして
     * 配送する。そのためにはネイティブのオーバーレイがタップを消費しないよう
     * 「透過」に設定できる必要がある。できない SDK ではネイティブのクリック
     * リスナーを使わざるを得ない。
     *
     * 実測（2026-08-09）:
     *  - Google Maps (play-services-maps 20.0.0): `Polygon` / `Polyline` / `Circle` /
     *    `GroundOverlay` は `clickable(false)` で透過し、`OnMapClickListener` が発火する。
     *    **`Marker` には `clickable` 自体が無く**、タップは常に消費される。
     *  - TomTom (SDK 2.4.1): `PolygonOptions` / `PolylineOptions` に `isClickable` は
     *    あるが、`false` にすると **透過ではなく握り潰し**になり、`MapClickListener`
     *    にも届かない（実機 Lenovo TB520FU で確認）。`MarkerOptions` には
     *    `isClickable` が無い。
     *
     * この capability が [MapCapabilityStatus.Unsupported] のプロバイダは、
     * ネイティブのクリックリスナー経由でイベントを受ける。判定自体はコアが行うので、
     * アプリから見た挙動は揃う。
     */
    ClickPassthrough("clickPassthrough"),

    // 操作
    MarkerDrag("markerDrag"),

    // カメラ
    CameraTilt("cameraTilt"),
    CameraRotate("cameraRotate"),
    CameraRestriction("cameraRestriction"),

    /**
     * 緯度経度と画面座標を **同期的に** 相互変換できるか。
     *
     * InfoBubble・マーカーアニメーション・タイル方式マーカーのヒットテストが
     * これを要求する。WebView ブリッジ経由のプロバイダ（android-for-longdo）は
     * 同期 API を持たないため対応できない。
     */
    ScreenProjectionSync("screenProjectionSync"),

    // ジェスチャ（[MapGesture] と 1 対 1）
    GestureScroll("gestureScroll"),
    GestureZoom("gestureZoom"),
    GestureRotate("gestureRotate"),
    GestureTilt("gestureTilt"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: String): MapCapability? = byId[id]
    }
}

/** [MapGesture] に対応する [MapCapability]。 */
val MapGesture.capability: MapCapability
    get() =
        when (this) {
            MapGesture.Scroll -> MapCapability.GestureScroll
            MapGesture.Zoom -> MapCapability.GestureZoom
            MapGesture.Rotate -> MapCapability.GestureRotate
            MapGesture.Tilt -> MapCapability.GestureTilt
        }

/**
 * ある [MapCapability] にプロバイダがどこまで応えられるか。
 *
 * 「未宣言（[Unknown]）」と「恒久的に非対応（[Unsupported]）」を区別できることが重要。
 * 区別が無いと、地図の初期化が終わっていないだけの状態と、その SDK では原理的に
 * できないことが同じに見えてしまう。
 */
sealed interface MapCapabilityStatus {
    /** 理由。[Supported] と [Unknown] は null。 */
    val reason: String?

    /** 期待どおりに動く。 */
    data object Supported : MapCapabilityStatus {
        override val reason: String? = null
    }

    /** 動くが結果が別物になる。例: HERE の穴付きポリゴンは塗りが和集合になる。 */
    data class Degraded(
        override val reason: String,
    ) : MapCapabilityStatus

    /** 動くが数値が近似。例: 円を多角形で近似する、ズーム換算に較正誤差がある。 */
    data class Approximated(
        override val reason: String,
    ) : MapCapabilityStatus

    /** この SDK では実現できない。 */
    data class Unsupported(
        override val reason: String,
    ) : MapCapabilityStatus

    /**
     * まだ宣言されていない。初期化途中か、プロバイダが宣言を書いていないかのどちらか。
     *
     * **[Unsupported] と同じに扱わないこと。** 非対応と断定してよいのは
     * [Unsupported] のときだけ。
     */
    data object Unknown : MapCapabilityStatus {
        override val reason: String? = null
    }

    /** 完全に期待どおりか（[Supported] のみ true）。 */
    val isFullySupported: Boolean get() = this is Supported

    /** 何らかの形で機能するか（[Degraded] / [Approximated] を含む）。 */
    val isUsable: Boolean get() = this is Supported || this is Degraded || this is Approximated

    /** 宣言されていて、かつ使えないと分かっているか。 */
    val isKnownUnsupported: Boolean get() = this is Unsupported
}
