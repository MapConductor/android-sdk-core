package com.mapconductor.core.map

/**
 * 同期の座標変換を要求する機能が、それを使えないプロバイダ上で動くときに
 * 「黙って無反応」にならないようにするための入口。
 *
 * ## なぜ要るか
 *
 * [MapViewHolderInterface.toScreenOffset] / [MapViewHolderInterface.fromScreenOffsetSync]
 * が null を返す理由は 2 つある。
 *
 *  1. その点が画面外・地表外（正常。毎フレーム起きる）
 *  2. **そのプロバイダが同期変換を持たない**（恒久的）
 *
 * 呼び出し側は null からこの 2 つを区別できない。2 の場合、機能が一切動かないのに
 * 何のログも出ないことになる。区別できるのは [MapCapability.ScreenProjectionSync] の
 * 宣言だけなので、ここで見る。
 *
 * ## Unsupported は「機能が動かない」ときだけ宣言すること
 *
 * ホルダーの API が同期変換を持たないことと、機能が動かないことは**別**。
 * android-for-longdo は [MapViewHolderInterface.toScreenOffset] が null を返すが、
 * オーバーレイの配置は Longdo の JS ブリッジ（`map.Renderer.project`）を使う独自経路で
 * 行っており、InfoBubble もマーカーも実際に動く。だから Longdo の宣言は
 * [MapCapabilityStatus.Degraded] であって Unsupported ではない。
 *
 * ここで見るのは**機能を落としてよいか**なので、Unsupported と明示されたときだけ
 * 落とす。Degraded / Approximated は動かす。
 *
 * ## Unknown を非対応と断定しない
 *
 * 宣言が無い（[MapCapabilityStatus.Unknown]）＝「まだ宣言していない」であって
 * 「使えない」ではない。地図の初期化途中もここに入る。**報告するのは
 * [MapCapabilityStatus.Unsupported] と明示されているときだけ**にして、
 * 未宣言のプロバイダを誤って告発しない。
 */
object ScreenProjectionRequirement {
    /**
     * 同期投影が使えるか。使えないと**分かっている**ときだけ 1 回報告して `false` を返す。
     *
     * @param feature ログに出す機能名（"InfoBubble" など）。何が動かないのかを
     *   読み手に伝えるため、capability の id ではなくこちらを出す。
     * @return 使える見込みがあれば `true`。`false` なら呼び出し側は機能を落とす。
     */
    fun check(
        registry: MapServiceRegistry,
        provider: String,
        feature: String,
    ): Boolean {
        val status = registry.capabilityStatus(MapCapability.ScreenProjectionSync)
        if (!status.isKnownUnsupported) return true
        MapDiagnostics.report(
            capability = MapCapability.ScreenProjectionSync,
            level = MapDiagnosticLevel.Unsupported,
            provider = provider,
            reason = status.reason ?: "this provider has no synchronous coordinate conversion",
            subject = feature,
        )
        return false
    }
}
