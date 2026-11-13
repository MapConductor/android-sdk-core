package com.mapconductor.core.map

import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.features.GeoRectBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [MapViewStyleHost.apply] の決定表。
 *
 * このクラスで実際に意味があるのは「いつ入れ直し、いつ入れ直さないか」だけで、
 * 残りはプロバイダへの受け渡しでしかない。そして一度その条件が逆になったまま
 * 気付かれなかった：**同じキーで `update` を呼び**（再コンパイル → 診断 →
 * state → 再コンポジション → 無限ループ。ユーザーには左下パネルのチラつきと
 * して見えた）、**キーが変わったときは `update` を呼ばなかった**（毎回
 * 破棄して入れ直す。差分をコンパイルする設計の意味がなくなる）。
 * 両方とも「コンパイルは通り地図も描ける」種類の壊れ方なので、ここで表に
 * して押さえる。
 *
 * ios-sdk の `MapViewStyleHostTests`、js-sdk-core の `MapViewStyleHost.test.mjs`
 * と 1 対 1 で対応する。
 */
class MapViewStyleHostTest {
    private val log = mutableListOf<String>()
    private lateinit var originalSink: MapDiagnostics.Sink

    @Before
    fun setUp() {
        originalSink = MapDiagnostics.sink
        MapDiagnostics.sink = MapDiagnostics.Sink { log.add(it) }
    }

    @After
    fun tearDown() {
        MapDiagnostics.sink = originalSink
    }

    /** 何が呼ばれたかだけを記録するスタイル。 */
    private class RecordingStyle(
        override val key: String,
        private val calls: MutableList<String>,
        /**
         * `update` が受けるかどうか。false なら呼び出し側は破棄して入れ直す。
         * 関数なのは、入れたあとに答えを変えられるようにするため（下の
         * 「連続カウントは戻る」）。
         */
        private val takesUpdates: () -> Boolean = { true },
    ) : MapViewStyle {
        override fun install(host: MapStyleHost): MapStyleInstallation {
            calls.add("install($key)")
            return object : MapStyleInstallation {
                override fun dispose() {
                    calls.add("dispose($key)")
                }

                override fun update(next: MapViewStyle): Boolean {
                    calls.add("update($key -> ${next.key})")
                    return takesUpdates()
                }
            }
        }
    }

    private val calls = mutableListOf<String>()

    private fun host(): MapViewStyleHost =
        MapViewStyleHost(MutableMapServiceRegistry(), NoController())

    private fun style(
        key: String,
        takesUpdates: Boolean = true,
    ) = RecordingStyle(key, calls) { takesUpdates }

    private fun style(
        key: String,
        takesUpdates: () -> Boolean,
    ) = RecordingStyle(key, calls, takesUpdates)

    @Test
    fun `最初の apply で入る`() {
        host().apply(style("a"))
        assertEquals(listOf("install(a)"), calls)
    }

    /**
     * キーが同じなら何もしない。これが逆になっていたのがチラつきの原因で、
     * 再コンパイルは無駄なだけでなく診断を再送し、それを描くアプリが state を
     * 更新して再コンポジションを呼ぶ。
     */
    @Test
    fun `キーが同じ apply は何も呼ばない`() {
        val host = host()
        host.apply(style("a"))
        calls.clear()

        // 中身の同じ別インスタンス。インライン生成されたスタイルはこうなる。
        host.apply(style("a"))
        host.apply(style("a"))

        assertEquals(emptyList<String>(), calls)
    }

    /**
     * キーが変わったら、まず今入っているものに渡す。受けられたなら地図は
     * ドキュメントを読み直さない — スライダーを動かして再読み込みが起きない
     * のはこの 1 行のおかげ。
     */
    @Test
    fun `キーが変わったら入れ直す前に update を試す`() {
        val host = host()
        host.apply(style("a"))
        calls.clear()

        host.apply(style("b"))

        assertEquals(listOf("update(a -> b)"), calls)
    }

    /** update が受けたなら、次は新しいキーが「同じ」の基準になる。 */
    @Test
    fun `update が受けたキーは据え置かれる`() {
        val host = host()
        host.apply(style("a"))
        host.apply(style("b"))
        calls.clear()

        host.apply(style("b"))

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `update を断られたら破棄して入れ直す`() {
        val host = host()
        host.apply(style("a", takesUpdates = false))
        calls.clear()

        host.apply(style("b", takesUpdates = false))

        assertEquals(listOf("update(a -> b)", "dispose(a)", "install(b)"), calls)
    }

    /**
     * `update` を持たないスタイル（`MapStyleInstallation.of` が返すもの）は
     * 既定で false。これが true 扱いになると、入れ替わっていないものに
     * 新しいキーが付く。
     */
    @Test
    fun `update を持たないスタイルは入れ直される`() {
        val plain =
            { key: String ->
                object : MapViewStyle {
                    override val key = key

                    override fun install(host: MapStyleHost): MapStyleInstallation {
                        calls.add("install($key)")
                        return MapStyleInstallation.of { calls.add("dispose($key)") }
                    }
                }
            }
        val host = host()
        host.apply(plain("a"))
        calls.clear()

        host.apply(plain("b"))

        assertEquals(listOf("dispose(a)", "install(b)"), calls)
    }

    /** 渡さなくなったら外す。スタイルが残した地図ではなく元の地図に戻る。 */
    @Test
    fun `null は外す`() {
        val host = host()
        host.apply(style("a"))
        calls.clear()

        host.apply(null)

        assertEquals(listOf("dispose(a)"), calls)
        // 外したあとに同じキーが来たら、据え置きではなく入れ直し。
        calls.clear()
        host.apply(style("a"))
        assertEquals(listOf("install(a)"), calls)
    }

    /**
     * 毎回断られるのは正しいが遅い。原因はたいてい `remember` に入れていない
     * ラスタライザなので、黙って遅くするのではなく言う。
     */
    @Test
    fun `断られ続けたら診断を出す`() {
        val host = host()
        host.apply(style("k0", takesUpdates = false))
        repeat(5) { host.apply(style("k${it + 1}", takesUpdates = false)) }

        assertEquals(1, log.count { it.contains("refused") })
        assertEquals(
            "直し方まで言う: identity 比較される値を作り直しているのが原因",
            1,
            log.count { it.contains("remember") },
        )
    }

    /**
     * 連続して断られたことだけが問題なので、1 回受かったら数え直す。
     * 入っているスタイル自身の答えを途中で変える必要があるため、ここだけ
     * フラグ越しに答えさせる。
     */
    @Test
    fun `update が受けたら連続カウントは戻る`() {
        val host = host()
        var takes = false
        host.apply(style("k0") { takes })
        repeat(4) { host.apply(style("r$it") { takes }) }
        takes = true
        host.apply(style("ok") { takes })
        takes = false
        repeat(4) { host.apply(style("s$it") { takes }) }

        assertEquals(emptyList<String>(), log.filter { it.contains("refused") })
    }
}

/**
 * コントローラは [MapViewStyleHost] の判断に一切関与しない（使うのは
 * `onStyleLoaded` / `upsertRaster` / `removeRaster` の受け渡しだけ）ので、
 * ここでは置けるだけの最小実装。
 */
private class NoController : BaseMapViewController() {
    override val holder get() = throw UnsupportedOperationException()
    override val defaultCoroutine = CoroutineScope(Dispatchers.Unconfined)
    override val mainCoroutine = defaultCoroutine

    override fun moveCamera(position: MapCameraPosition) = Unit

    override fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    ) = Unit

    override fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    ) = Unit

    override suspend fun clearOverlays() = Unit
}
