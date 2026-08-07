package com.mapconductor.core.marker

import androidx.annotation.RestrictTo

/**
 * ビューポート内のマーカーが十分少ないときだけ、マーカータイリングをやめて
 * ネイティブマーカーで描くための閾値。[MarkerViewportSwitch] が使う。
 *
 * **開発中の機能で、公開 API ではない。** SDK 利用者が値を差し込む口は無く
 * （[MarkerTilingOptions.viewport] は読み取り専用の固定値）、プロバイダモジュールから
 * 参照するためだけに public になっている。
 *
 * マーカータイリング（[MarkerTileRenderer]）は数万件を捌ける代わりに、絵をラスタータイルへ
 * 焼くので (1) 地図を回すとアイコンごと傾く (2) ドラッグやアニメーションのような per-marker の
 * 動きを持てない、という欠点がある。一方ネイティブマーカーはその 2 つが自然に効く代わりに
 * 件数に弱い。そこで「画面とその周辺に [nativeMaxCount] 件しか無いなら、その区間だけ
 * ネイティブに戻す」という切り替えを入れる。
 *
 * **切り替えはラスターレイヤ丸ごとの表示／非表示で行い、タイルの中身は変えない。**
 * タイルの内容（どの entity が [MarkerEntityInterface.tiling] か）を変えてしまうと
 * [MarkerTileRenderer.invalidate] とタイル URL の更新が必要になり、カメラを動かすたびに
 * 可視タイルを取り直すことになる。中身を据え置けばタイルキャッシュはそのまま生き、
 * 戻すときもキャッシュから即出る。
 *
 * ### アイコンの大きさに注意
 *
 * [MarkerTilingOptions.iconScaleCallback] はタイルへ焼くときだけ効き、ネイティブへ昇格した
 * マーカーには効かない（ネイティブマーカーの絵をズームごとに作り直さないことがタイリングを
 * 使う理由なので）。ズーム依存の倍率を入れていると、切り替わった瞬間にアイコンの大きさが
 * 変わって見える。揃えたい場合は、切り替えが起きるズーム帯で callback が 1.0 を返すようにする。
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class MarkerViewportPolicy(
    /** false ならタイリング済みマーカーをネイティブへ戻さない（従来どおり常にタイル）。 */
    val enabled: Boolean = true,
    /** 拡張ビューポート内のタイル担当マーカーがこの数以下なら、ネイティブへ切り替える。 */
    val nativeMaxCount: Int = 500,
    /**
     * ネイティブ中に拡張ビューポート内がこの数以上になったら、タイルへ戻す。
     *
     * [nativeMaxCount] と同じ値にすると境界で往復するため、必ず大きめに取る（ヒステリシス）。
     */
    val tileMinCount: Int = 600,
    /**
     * このズームより引いているときは常にタイル。0.0 ならズームでは切り替えない。
     *
     * 件数だけで判断すると、疎な地域を広域表示したときにもネイティブへ落ちる。それ自体は
     * 破綻しないが、切り替わりを近距離だけに閉じ込めたい場合にここを上げる。
     */
    val minZoom: Double = 0.0,
    /** ビューポートを広げる割合。[com.mapconductor.core.spherical.expandBounds] に渡す。 */
    val expandMargin: Double = 0.2,
    /**
     * カメラが止まったとみなすまでの待ち時間。
     *
     * パン中の全フレームで判定すると、閾値をまたぐたびに数百件の add/remove が走る。
     * オーバーレイ収集の 5ms（`Settings.Default.composeEventDebounce`）では短すぎるので、
     * ここは指を離してから動く程度に長く取る。
     */
    val settleDelayMillis: Long = 150,
    /**
     * モードや昇格件数が変わったときに呼ばれる。デモ・デバッグ表示用。
     *
     * 判定コルーチン（メインディスパッチャ）から呼ばれる。重い処理を書かないこと。
     *
     * @param isNative ネイティブマーカーで描いているなら true（＝タイルのレイヤは隠れている）
     * @param promotedCount いまネイティブへ昇格させている件数
     */
    val onModeChanged: ((isNative: Boolean, promotedCount: Int) -> Unit)? = null,
) {
    init {
        require(tileMinCount > nativeMaxCount) {
            "tileMinCount ($tileMinCount) must be greater than nativeMaxCount ($nativeMaxCount) " +
                "so the switch has hysteresis"
        }
    }

    companion object {
        val Default: MarkerViewportPolicy = MarkerViewportPolicy()
        val Disabled: MarkerViewportPolicy = MarkerViewportPolicy(enabled = false)
    }
}
