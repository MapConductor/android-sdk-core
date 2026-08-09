package com.mapconductor.core.controller

import com.mapconductor.core.features.GeoPointInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Capable ファサードのスロット解決の意味論テスト。
 *
 * ## 背景（作り込んだ不具合）
 *
 * Step 4 で [OverlayKind] による振り分けを入れたとき、
 * `AbstractMarkerController` と `StrategyMarkerController` への `kind` 宣言を
 * 忘れた。`primaryOverlayController(Marker)` が null を返し、
 * `compositionMarkers` が**黙って何もしない**状態になり、全プロバイダで
 * マーカーが一切表示されなくなった。
 *
 * しかも **ビルドも apiCheck もユニットテストも全部緑だった**:
 *  - `null?.add(...)` は型として正しい
 *  - API 差分にも現れない
 *  - 既定実装をフェイクで検証していたコアのテストも通る
 *
 * ## 対策は「テストで捕まえる」ではなく「コンパイルエラーにする」
 *
 * 最初はリフレクションで `getKind` の宣言有無を見るテストを書いたが、**効かなかった**。
 * Kotlin の interface 既定プロパティは実装クラス側にも getter を生成するため、
 * 宣言の有無を `declaredMethods` で区別できない。
 *
 * そこで [SlottedOverlayController] を分離し、`kind` を**抽象**にした。
 * 宣言を忘れると具象クラスがコンパイルエラーになる（実証済み:
 * AbstractMarkerController から消すと MapLibreMarkerController が
 * "is not abstract and does not implement abstract member" で落ちる）。
 *
 * このテストが見るのは、その仕組みの上に載る**解決規則**の方。
 */
class OverlayKindCoverageTest {
    private class FakeSlotted(
        override val kind: OverlayKind,
        private val ids: Set<String> = emptySet(),
    ) : SlottedOverlayController<String, String> {
        override val zIndex: Int = 0
        val added = mutableListOf<List<String>>()

        override suspend fun add(data: List<String>) {
            added += data
        }

        override suspend fun update(state: String) = Unit

        override suspend fun clear() = Unit

        override fun find(position: GeoPointInterface): String? = null

        override fun has(id: String): Boolean = id in ids

        override fun destroy() = Unit
    }

    /** スロットに参加しない、拡張モジュール相当のコントローラ。 */
    private class FakePlain : OverlayControllerInterface<String, String> {
        override val zIndex: Int = 0

        override suspend fun add(data: List<String>) = Unit

        override suspend fun update(state: String) = Unit

        override suspend fun clear() = Unit

        override fun find(position: GeoPointInterface): String? = null

        override fun destroy() = Unit
    }

    @Test
    fun `OverlayKind は Capable と同じ 6 種別`() {
        // marker / polyline / polygon / circle / groundImage / rasterLayer。
        // 種別が増減したらここで気づく。
        assertEquals(6, OverlayKind.entries.size)
        assertEquals(OverlayKind.entries.size, OverlayKind.entries.distinct().size)
    }

    @Test
    fun `スロットに参加しないコントローラは has が既定で false`() {
        val plain = FakePlain()
        assertFalse(plain.has("anything"))
        // SlottedOverlayController ではないので Capable の振り分け対象にならない
        // （型の上で保証されるので、ここでは has の既定だけを見る）。
    }

    @Test
    fun `SlottedOverlayController は kind を必ず持つ`() {
        // kind は抽象。実装を忘れるとコンパイルが通らないので、
        // ここでは「非 null で取れること」だけを型の上で確認する。
        val slotted: SlottedOverlayController<String, String> = FakeSlotted(OverlayKind.Marker)
        assertSame(OverlayKind.Marker, slotted.kind)
    }

    @Test
    fun `同じ種別を複数登録しても has はどれかが持てば true`() {
        // クラスタリングは Marker 種別で追加のコントローラを登録する。
        val a = FakeSlotted(OverlayKind.Marker, ids = setOf("a"))
        val b = FakeSlotted(OverlayKind.Marker, ids = setOf("b"))
        val controllers = listOf<SlottedOverlayController<*, *>>(a, b)

        assertEquals(true, controllers.filter { it.kind == OverlayKind.Marker }.any { it.has("a") })
        assertEquals(true, controllers.filter { it.kind == OverlayKind.Marker }.any { it.has("b") })
        assertEquals(false, controllers.filter { it.kind == OverlayKind.Marker }.any { it.has("c") })
    }

    @Test
    fun `種別が違えば別スロット`() {
        val marker = FakeSlotted(OverlayKind.Marker, ids = setOf("x"))
        val polygon = FakeSlotted(OverlayKind.Polygon, ids = setOf("x"))
        val controllers = listOf<SlottedOverlayController<*, *>>(marker, polygon)

        assertSame(marker, controllers.first { it.kind == OverlayKind.Marker })
        assertSame(polygon, controllers.first { it.kind == OverlayKind.Polygon })
        assertNull(controllers.firstOrNull { it.kind == OverlayKind.Circle })
    }
}
