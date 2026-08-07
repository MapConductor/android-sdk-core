package com.mapconductor.core.raster

import java.lang.ref.WeakReference
import java.net.URI
import android.util.Log

/** 「この宛先へのリクエストにはこのヘッダを載せる」という 1 件の規則。 */
data class RasterHeaderRule(
    /** 適用先ホスト（小文字化済み）。 */
    val host: String,
    /** 適用先ポート。URL に明示が無ければ `null`。 */
    val port: Int?,
    /** 差し替える User-Agent。空文字・空白のみは `null` に丸める。 */
    val userAgent: String?,
    /** 追加ヘッダ。 */
    val extraHeaders: Map<String, String>,
)

/**
 * [RasterLayerState] の `userAgent` / `extraHeaders` を、**宛先ホスト単位**で管理する。
 *
 * ## なぜホスト単位か
 *
 * MapLibre 系のプロバイダはリクエスト書き換えのフックが
 * `HttpRequestUtil.setOkHttpClient()`、つまり**プロセス全体に 1 つ**しかない。
 * 何も考えずにここへヘッダを差すと、ラスタレイヤ用のヘッダが**ベースマップの
 * スタイルやベクタタイルの取得にも載る**。ラスタレイヤの既定 User-Agent は
 * 空ではない（[RasterLayerState.DEFAULT_USER_AGENT]）ので、ラスタレイヤを 1 枚
 * 置いただけで地図全体の User-Agent が書き換わってしまう。
 *
 * 宛先ホストで絞れば、そのラスタタイルを配信しているサーバ宛だけに載る。
 *
 * ## なぜ共有シングルトンか
 *
 * `android-for-maplibre` と `android-for-maptiler` は別モジュールだが、依存する
 * MapLibre SDK は同じ 1 つ。つまり `HttpRequestUtil` のクライアントも 1 つで、
 * 両者が別々に差すと後勝ちで一方が無効になる。規則の置き場を [shared] に
 * 一本化しておけば、どちらのクライアントが最終的に載っていても同じ結果になる。
 *
 * OkHttp の Interceptor は**ワーカースレッドから呼ばれる**ため、全アクセスを同期する。
 *
 * ios-sdk の `RasterHeaderRuleSet` と同じ構造・同じ意味論。
 */
class RasterHeaderRuleSet {
    /**
     * 登録元 1 つ分の規則。
     *
     * 登録元（コントローラ）は弱参照で持つ。`remove` を呼び忘れても地図ごと
     * リークさせないため。参照が切れた項目は次の操作でまとめて捨てる。
     */
    private class Entry(
        owner: Any,
        val rules: List<RasterHeaderRule>,
    ) {
        val owner = WeakReference(owner)
    }

    private val lock = Any()

    /** 登録順に持つ。順序が決まっていないと、競合時にどの規則が勝つか再現しない。 */
    private val entries = mutableListOf<Entry>()

    /** 規則が 1 件も無いか。`true` のときフックを外してよい（既定の挙動に戻す）。 */
    val isEmpty: Boolean
        get() =
            synchronized(lock) {
                purgeLocked()
                entries.all { it.rules.isEmpty() }
            }

    /** 登録元 1 つ分の規則を差し替える。 */
    fun setRules(
        rules: List<RasterHeaderRule>,
        owner: Any,
    ) {
        synchronized(lock) {
            purgeLocked()
            entries.removeAll { it.owner.get() === owner }
            if (rules.isNotEmpty()) {
                entries.add(Entry(owner, rules))
            }
        }
    }

    /** 登録元 1 つ分の規則を外す（`unbind` / 破棄時）。 */
    fun removeRules(owner: Any) {
        synchronized(lock) {
            purgeLocked()
            entries.removeAll { it.owner.get() === owner }
        }
    }

    /**
     * [url] に適用すべきヘッダ。該当が無ければ `null`。
     *
     * 同じホストに複数のラスタレイヤがあり値が食い違う場合、書き換えフックが
     * プロセス全体に 1 つしかない以上どれか 1 つしか選べない。**後勝ち**にはせず、
     * ホストとポートが一致する最初の規則を使う（順序が決まるので結果が再現する）。
     */
    fun headersFor(url: String): RasterHeaderRule? {
        val uri =
            try {
                URI(url)
            } catch (e: Exception) {
                return null
            }
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port >= 0) uri.port else null

        val snapshot =
            synchronized(lock) {
                purgeLocked()
                entries.flatMap { it.rules }
            }

        return snapshot.firstOrNull { rule ->
            rule.host == host &&
                (rule.port == null || rule.port == port) &&
                (rule.userAgent != null || rule.extraHeaders.isNotEmpty())
        }
    }

    private fun purgeLocked() {
        entries.removeAll { it.owner.get() == null }
    }

    companion object {
        /** プロバイダ横断で共有する実体。 */
        val shared = RasterHeaderRuleSet()

        /** レイヤの状態から規則を組み立てる。ヘッダ指定が無い状態は規則を作らない。 */
        fun makeRules(states: List<RasterLayerState>): List<RasterHeaderRule> =
            states.mapNotNull { state ->
                val uri =
                    try {
                        URI(templateUrlString(state.source))
                    } catch (e: Exception) {
                        null
                    }
                val host = uri?.host?.lowercase()
                if (host.isNullOrEmpty()) return@mapNotNull null

                val userAgent = state.userAgent.trim().ifEmpty { null }
                val extraHeaders = state.extraHeaders ?: emptyMap()
                if (userAgent == null && extraHeaders.isEmpty()) return@mapNotNull null

                RasterHeaderRule(
                    host = host,
                    port = if (uri.port >= 0) uri.port else null,
                    userAgent = userAgent,
                    extraHeaders = extraHeaders,
                )
            }

        /**
         * ヘッダを載せられないプロバイダが、黙って無視せずに知らせるための共通口。
         *
         * ネイティブ SDK がリクエストの書き換えを一切公開していないプロバイダがある。
         * 指定が効かないこと自体は仕様としてドキュメントに書くが、**実行時にも分かる**
         * ようにしておかないと、利用者は「認証が通らない理由」を自分のサーバ側で探すことになる。
         *
         * @param supportsUserAgent `userAgent` だけは載せられるプロバイダは `true`。
         */
        fun warnUnsupported(
            provider: String,
            state: RasterLayerState,
            supportsUserAgent: Boolean = false,
        ) {
            val ignored = mutableListOf<String>()
            if (!supportsUserAgent) {
                val ua = state.userAgent.trim()
                if (ua.isNotEmpty() && ua != RasterLayerState.DEFAULT_USER_AGENT) {
                    ignored.add("userAgent")
                }
            }
            if (!state.extraHeaders.isNullOrEmpty()) {
                ignored.add("extraHeaders")
            }
            if (ignored.isEmpty()) return

            // 同じレイヤで何度も出さない。更新経路は頻繁に走る。
            val key = "$provider|${state.id}|${ignored.joinToString(",")}"
            val isNew = synchronized(warnedKeys) { warnedKeys.add(key) }
            if (!isNew) return

            Log.w(
                "MapConductor",
                "$provider RasterLayer: ${ignored.joinToString(" / ")} is not supported " +
                    "on Android and will be ignored. id=${state.id}",
            )
        }

        private val warnedKeys = mutableSetOf<String>()

        /**
         * [RasterLayerSource] から、ホストを取り出せる形の URL 文字列にする。
         *
         * `{z}/{x}/{y}` のような差し込み記法が入ったままだと URI として解釈できない
         * ことがあるので、最初の `{` より前で切る。ホストが取れれば十分。
         */
        private fun templateUrlString(source: RasterLayerSource): String {
            val raw =
                when (source) {
                    is RasterLayerSource.UrlTemplate -> source.template
                    is RasterLayerSource.TileJson -> source.url
                    is RasterLayerSource.ArcGisService -> source.serviceUrl
                }
            val brace = raw.indexOf('{')
            return if (brace < 0) raw else raw.substring(0, brace)
        }
    }
}
