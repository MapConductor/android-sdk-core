package com.mapconductor.core.raster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ラスタタイルのヘッダ規則を、**宛先ホスト単位**で持つという約束を固定する。
 *
 * MapLibre 系のフックはプロセス全体に 1 つしか無いので、ホストで絞れていないと
 * ラスタレイヤ用のヘッダがベースマップの取得にも載る。ここが緩むと静かに漏れる。
 *
 * ios-sdk の `RasterHeaderRuleSetTests` と対になっている。
 */
class RasterHeaderRuleSetTest {
    private fun state(
        template: String,
        userAgent: String = RasterLayerState.DEFAULT_USER_AGENT,
        extraHeaders: Map<String, String>? = null,
        id: String = "layer",
    ) = RasterLayerState(
        source = RasterLayerSource.UrlTemplate(template = template),
        userAgent = userAgent,
        extraHeaders = extraHeaders,
        id = id,
    )

    @Test
    fun `makeRules extracts host and port from template`() {
        val rules =
            RasterHeaderRuleSet.makeRules(
                listOf(state("https://tiles.example.com:8443/{z}/{x}/{y}.png")),
            )

        assertEquals(1, rules.size)
        assertEquals("tiles.example.com", rules[0].host)
        assertEquals(8443, rules[0].port)
    }

    /** `{z}` を含んだままだと URI として解釈できないことがある。切ってからホストを取る。 */
    @Test
    fun `makeRules handles template without port`() {
        val rules =
            RasterHeaderRuleSet.makeRules(
                listOf(state("https://tiles.example.com/{z}/{x}/{y}.png")),
            )

        assertEquals("tiles.example.com", rules[0].host)
        assertNull(rules[0].port)
    }

    /** ヘッダ指定が何も無い状態は規則を作らない。作ると無駄にフックが載る。 */
    @Test
    fun `makeRules skips state without any header`() {
        val rules =
            RasterHeaderRuleSet.makeRules(
                listOf(state("https://tiles.example.com/{z}/{x}/{y}.png", userAgent = "  ")),
            )

        assertTrue(rules.isEmpty())
    }

    @Test
    fun `headers match only the declared host`() {
        val set = RasterHeaderRuleSet()
        val owner = Any()
        set.setRules(
            RasterHeaderRuleSet.makeRules(
                listOf(
                    state(
                        template = "https://tiles.example.com/{z}/{x}/{y}.png",
                        userAgent = "Probe/1.0",
                        extraHeaders = mapOf("X-Token" to "abc"),
                    ),
                ),
            ),
            owner,
        )

        val hit = set.headersFor("https://tiles.example.com/3/1/2.png")
        assertEquals("Probe/1.0", hit?.userAgent)
        assertEquals("abc", hit?.extraHeaders?.get("X-Token"))

        // ベースマップが別ホストなら載らない。ここが漏れると地図全体の UA が変わる。
        assertNull(set.headersFor("https://basemap.example.org/style.json"))
    }

    /** ポートを明示した規則は、そのポート宛にだけ載る。 */
    @Test
    fun `headers respect port`() {
        val set = RasterHeaderRuleSet()
        set.setRules(
            RasterHeaderRuleSet.makeRules(
                listOf(state("http://127.0.0.1:9000/{z}/{x}/{y}.png", userAgent = "Probe/1.0")),
            ),
            Any(),
        )

        assertEquals("Probe/1.0", set.headersFor("http://127.0.0.1:9000/1/0/0.png")?.userAgent)
        assertNull(set.headersFor("http://127.0.0.1:9001/1/0/0.png"))
    }

    /** 地図が複数あっても互いの規則を消さない。登録元ごとに分けて持つ。 */
    @Test
    fun `rules are scoped per owner`() {
        val set = RasterHeaderRuleSet()
        val first = Any()
        val second = Any()

        set.setRules(
            RasterHeaderRuleSet.makeRules(
                listOf(state("https://a.example.com/{z}/{x}/{y}.png", userAgent = "A/1.0", id = "a")),
            ),
            first,
        )
        set.setRules(
            RasterHeaderRuleSet.makeRules(
                listOf(state("https://b.example.com/{z}/{x}/{y}.png", userAgent = "B/1.0", id = "b")),
            ),
            second,
        )

        assertEquals("A/1.0", set.headersFor("https://a.example.com/1/0/0.png")?.userAgent)
        assertEquals("B/1.0", set.headersFor("https://b.example.com/1/0/0.png")?.userAgent)

        set.removeRules(first)
        assertNull(set.headersFor("https://a.example.com/1/0/0.png"))
        assertEquals("B/1.0", set.headersFor("https://b.example.com/1/0/0.png")?.userAgent)
    }

    /** 全部外れたら空になる。フックを外してよい合図。 */
    @Test
    fun `isEmpty reflects registrations`() {
        val set = RasterHeaderRuleSet()
        val owner = Any()
        assertTrue(set.isEmpty)

        set.setRules(
            RasterHeaderRuleSet.makeRules(
                listOf(state("https://a.example.com/{z}/{x}/{y}.png", userAgent = "A/1.0")),
            ),
            owner,
        )
        assertFalse(set.isEmpty)

        set.removeRules(owner)
        assertTrue(set.isEmpty)
    }
}
