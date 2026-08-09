package com.mapconductor.core.map

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ScreenProjectionRequirement] の意味論テスト。
 *
 * 守りたい不変条件は 1 つ:
 * **未宣言（[MapCapabilityStatus.Unknown]）を非対応と断定しない。**
 *
 * 宣言が無いのは「まだ宣言していない」か「地図の初期化途中」であって
 * 「使えない」ではない。ここで誤って機能を落とすと、宣言をまだ書いていない
 * プロバイダで InfoBubble やマーカーアニメーションが動かなくなる。
 */
class ScreenProjectionRequirementTest {
    private val messages = mutableListOf<String>()
    private lateinit var originalSink: MapDiagnostics.Sink

    @Before
    fun setUp() {
        originalSink = MapDiagnostics.sink
        MapDiagnostics.sink = MapDiagnostics.Sink { messages.add(it) }
        MapDiagnostics.resetWarnings()
    }

    @After
    fun tearDown() {
        MapDiagnostics.sink = originalSink
        MapDiagnostics.resetWarnings()
    }

    @Test
    fun `未宣言なら使える前提で通す`() {
        val registry = MutableMapServiceRegistry()
        assertTrue(ScreenProjectionRequirement.check(registry, "Whatever", "InfoBubble"))
        assertTrue("未宣言で警告を出してはいけない", messages.isEmpty())
    }

    @Test
    fun `Supported なら通す`() {
        val registry =
            MutableMapServiceRegistry().apply {
                declare(MapCapability.ScreenProjectionSync, MapCapabilityStatus.Supported)
            }
        assertTrue(ScreenProjectionRequirement.check(registry, "MapLibre", "InfoBubble"))
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `Unsupported なら落として理由を報告する`() {
        val registry =
            MutableMapServiceRegistry().apply {
                declareUnsupported(
                    MapCapability.ScreenProjectionSync,
                    "Longdo runs on a WebView bridge with no synchronous project/unproject",
                )
            }

        assertFalse(ScreenProjectionRequirement.check(registry, "Longdo", "InfoBubble"))

        val message = messages.single()
        assertTrue("何が動かないのかを出す", message.startsWith("InfoBubble"))
        assertTrue("理由を出す", message.contains("WebView bridge"))
        assertTrue("どのプロバイダかを出す", message.contains("Longdo"))
    }

    @Test
    fun `報告は 1 回だけ（毎フレーム呼ばれても溢れない）`() {
        val registry =
            MutableMapServiceRegistry().apply {
                declareUnsupported(MapCapability.ScreenProjectionSync, "no sync")
            }
        repeat(100) { ScreenProjectionRequirement.check(registry, "Longdo", "InfoBubble") }
        assertEquals(1, messages.size)
    }

    @Test
    fun `Degraded や Approximated は落とさない`() {
        // 「使えるが完全ではない」は動かす。落とすのは Unsupported のときだけ。
        listOf(
            MapCapabilityStatus.Degraded("partially"),
            MapCapabilityStatus.Approximated("rounded"),
        ).forEach { status ->
            MapDiagnostics.resetWarnings()
            messages.clear()
            val registry =
                MutableMapServiceRegistry().apply {
                    declare(MapCapability.ScreenProjectionSync, status)
                }
            assertTrue("$status で落としてはいけない", ScreenProjectionRequirement.check(registry, "P", "InfoBubble"))
            assertTrue(messages.isEmpty())
        }
    }
}
