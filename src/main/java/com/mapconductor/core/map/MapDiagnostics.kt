package com.mapconductor.core.map

import android.util.Log

/**
 * プロバイダが応えられなかった要求を報告する。
 *
 * [MapUISettingsDiagnostics.warnIfRequested] を全 capability へ一般化したもの。
 * 元の実装が持っていた 2 つの正しい判断をそのまま引き継いでいる:
 *
 *  1. **アプリが実際にその機能を要求したときだけ報告する。** 起動時に非対応一覧を
 *     吐くとノイズになって読まれない。
 *  2. **provider + capability + level ごとに 1 回だけ。** 毎フレーム再コンポーズ
 *     しても logcat が溢れない。
 *
 * 出力先は [sink] で差し替えられる。`android.util.Log` は JVM のユニットテストでは
 * 使えないため、テストからは sink を置き換えて検証する。
 */
object MapDiagnostics {
    private const val TAG = "MapConductor"

    /** 報告の出力先。 */
    fun interface Sink {
        fun log(message: String)
    }

    /** 既定は logcat の warning。テストや独自ロガーに差し替えられる。 */
    @Volatile
    var sink: Sink = Sink { Log.w(TAG, it) }

    private val reported = mutableSetOf<String>()

    /**
     * 要求に応えられなかったことを 1 回だけ報告する。
     *
     * @param subject ログに出す名前。既定は [MapCapability.id]。要求と設定名が
     *   食い違う場合（ジェスチャ設定など）に上書きする。
     * @return 実際に報告したら true（同じ内容の 2 回目以降は false）。
     */
    fun report(
        capability: MapCapability,
        level: MapDiagnosticLevel,
        provider: String,
        reason: String,
        subject: String = capability.id,
    ): Boolean {
        val key = "$provider.${capability.name}.${level.name}"
        synchronized(reported) {
            if (!reported.add(key)) return false
        }
        sink.log("$subject ${level.phrase} $provider ($reason); ${level.consequence}")
        return true
    }

    /**
     * [requested] が true のとき（＝アプリがその機能を実際に要求したとき）だけ報告する。
     *
     * 要求していない機能について警告しても行動につながらないので黙る。
     */
    fun reportIfRequested(
        requested: Boolean,
        capability: MapCapability,
        level: MapDiagnosticLevel,
        provider: String,
        reason: String,
        subject: String = capability.id,
    ): Boolean {
        if (!requested) return false
        return report(capability, level, provider, reason, subject)
    }

    /** テスト用フック — どの報告を済ませたかを忘れる。 */
    fun resetWarnings() {
        synchronized(reported) { reported.clear() }
    }
}

/**
 * 要求に応えられなかった度合い。[MapCapabilityStatus] と対応するが、
 * こちらは「いま起きた 1 回の出来事」を表す。
 */
enum class MapDiagnosticLevel(
    internal val phrase: String,
    internal val consequence: String,
) {
    /** 出せない。 */
    Unsupported("is not supported by", "the request is ignored."),

    /** 出るが別物になる。 */
    Degraded("is only partially supported by", "the result differs from other providers."),

    /** 数値が近似になる。 */
    Approximated("is approximated by", "values may differ slightly from other providers."),

    /** 要求を捨てた。 */
    Ignored("cannot be changed on", "the setting is ignored."),
}
