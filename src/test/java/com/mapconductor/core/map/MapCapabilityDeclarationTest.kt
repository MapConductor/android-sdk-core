package com.mapconductor.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * capability 宣言の意味論テスト。
 *
 * 守りたい不変条件は 2 つ:
 *  - **「未宣言」と「非対応」を混同しない。** 初期化途中のマップと、その SDK では
 *    原理的にできないことを、呼び出し側が区別できること。
 *  - **登録トークンで自分の登録だけを外せる。** キー名を列挙した撤収コードを
 *    書かなくて済むこと（ios-sdk の `removeProviderRegistrations()` が固定 2 キーを
 *    ハードコードしていた形の解消）。
 */
class MapCapabilityDeclarationTest {
    private object PlainKey : MapServiceKey<String>

    private object DragKey : MapServiceKey<String> {
        override val capability = MapCapability.MarkerDrag
    }

    // ── 未宣言 vs 非対応 ────────────────────────────────────────────────

    @Test
    fun `宣言が無ければ Unknown であって Unsupported ではない`() {
        val registry = MutableMapServiceRegistry()
        val status = registry.capabilityStatus(MapCapability.GroundImage)

        assertSame(MapCapabilityStatus.Unknown, status)
        assertFalse("未宣言を非対応と断定してはいけない", status.isKnownUnsupported)
        assertFalse(status.isUsable)
        assertNull(status.reason)
    }

    @Test
    fun `declareUnsupported は理由つきで非対応を宣言する`() {
        val registry = MutableMapServiceRegistry()
        registry.declareUnsupported(
            MapCapability.ScreenProjectionSync,
            "Longdo JS bridge has no synchronous unproject",
        )

        val status = registry.capabilityStatus(MapCapability.ScreenProjectionSync)
        assertTrue(status.isKnownUnsupported)
        assertFalse(status.isUsable)
        assertEquals("Longdo JS bridge has no synchronous unproject", status.reason)
    }

    @Test
    fun `Degraded と Approximated は使えるが完全ではない`() {
        val registry = MutableMapServiceRegistry()
        registry.declare(MapCapability.PolygonHoles, MapCapabilityStatus.Degraded("fill becomes a union"))
        registry.declare(MapCapability.Circle, MapCapabilityStatus.Approximated("drawn as a polygon"))

        val holes = registry.capabilityStatus(MapCapability.PolygonHoles)
        assertTrue(holes.isUsable)
        assertFalse(holes.isFullySupported)
        assertFalse(holes.isKnownUnsupported)

        val circle = registry.capabilityStatus(MapCapability.Circle)
        assertTrue(circle.isUsable)
        assertFalse(circle.isFullySupported)
    }

    // ── put による自動宣言 ──────────────────────────────────────────────

    @Test
    fun `capability を持つキーを put すると Supported になる`() {
        val registry = MutableMapServiceRegistry()
        assertSame(MapCapabilityStatus.Unknown, registry.capabilityStatus(MapCapability.MarkerDrag))

        registry.put(DragKey, "impl")

        assertSame(MapCapabilityStatus.Supported, registry.capabilityStatus(MapCapability.MarkerDrag))
    }

    @Test
    fun `capability を持たないキーは何も宣言しない`() {
        val registry = MutableMapServiceRegistry()
        registry.put(PlainKey, "impl")

        assertTrue(registry.has(PlainKey))
        assertEquals(emptyMap<MapCapability, MapCapabilityStatus>(), registry.declaredCapabilities())
    }

    @Test
    fun `has は登録の有無を返す`() {
        val registry = MutableMapServiceRegistry()
        assertFalse(registry.has(PlainKey))
        registry.put(PlainKey, "impl")
        assertTrue(registry.has(PlainKey))
    }

    // ── 登録トークン ────────────────────────────────────────────────────

    @Test
    fun `dispose は自分の登録だけを外す`() {
        val registry = MutableMapServiceRegistry()
        val plain = registry.register(PlainKey, "plain")
        registry.put(DragKey, "drag")

        plain.dispose()

        assertNull(registry.get(PlainKey))
        assertSame("drag", registry.get(DragKey))
        assertSame(MapCapabilityStatus.Supported, registry.capabilityStatus(MapCapability.MarkerDrag))
    }

    @Test
    fun `dispose は capability の宣言も戻す`() {
        val registry = MutableMapServiceRegistry()
        val registration = registry.register(DragKey, "drag")
        assertSame(MapCapabilityStatus.Supported, registry.capabilityStatus(MapCapability.MarkerDrag))

        registration.dispose()

        assertSame(MapCapabilityStatus.Unknown, registry.capabilityStatus(MapCapability.MarkerDrag))
    }

    @Test
    fun `上書きされた後の dispose は新しい値を消さない`() {
        val registry = MutableMapServiceRegistry()
        val first = registry.register(PlainKey, "first")
        registry.put(PlainKey, "second")

        first.dispose()

        assertSame("上書き後の値まで消してはいけない", "second", registry.get(PlainKey))
    }

    @Test
    fun `declare の dispose は直前の宣言へ戻す`() {
        val registry = MutableMapServiceRegistry()
        registry.declare(MapCapability.CameraTilt, MapCapabilityStatus.Supported)
        val second = registry.declare(MapCapability.CameraTilt, MapCapabilityStatus.Degraded("emulated"))

        second.dispose()

        assertSame(MapCapabilityStatus.Supported, registry.capabilityStatus(MapCapability.CameraTilt))
    }

    @Test
    fun `MapServiceRegistrations は登録をまとめて外す`() {
        val registry = MutableMapServiceRegistry()
        val registrations = MapServiceRegistrations()
        registrations += registry.register(PlainKey, "plain")
        registrations += registry.register(DragKey, "drag")
        registrations += registry.declareUnsupported(MapCapability.GroundImage, "no API")

        registrations.disposeAll()

        assertNull(registry.get(PlainKey))
        assertNull(registry.get(DragKey))
        assertSame(MapCapabilityStatus.Unknown, registry.capabilityStatus(MapCapability.MarkerDrag))
        assertSame(MapCapabilityStatus.Unknown, registry.capabilityStatus(MapCapability.GroundImage))
    }

    @Test
    fun `disposeAll の二重呼び出しは安全`() {
        val registry = MutableMapServiceRegistry()
        val registrations = MapServiceRegistrations()
        registrations += registry.register(PlainKey, "plain")

        registrations.disposeAll()
        registrations.disposeAll()

        assertNull(registry.get(PlainKey))
    }

    // ── 既存 API との共存 ───────────────────────────────────────────────

    @Test
    fun `remove は capability の宣言も取り下げる`() {
        val registry = MutableMapServiceRegistry()
        registry.put(DragKey, "drag")

        registry.remove(DragKey)

        assertNull(registry.get(DragKey))
        assertSame(MapCapabilityStatus.Unknown, registry.capabilityStatus(MapCapability.MarkerDrag))
    }

    @Test
    fun `clear は宣言も消す`() {
        val registry = MutableMapServiceRegistry()
        registry.put(DragKey, "drag")
        registry.declareUnsupported(MapCapability.GroundImage, "no API")

        registry.clear()

        assertSame(MapCapabilityStatus.Unknown, registry.capabilityStatus(MapCapability.MarkerDrag))
        assertSame(MapCapabilityStatus.Unknown, registry.capabilityStatus(MapCapability.GroundImage))
    }

    @Test
    fun `EmptyMapServiceRegistry は常に Unknown`() {
        assertSame(
            MapCapabilityStatus.Unknown,
            EmptyMapServiceRegistry.capabilityStatus(MapCapability.Marker),
        )
        assertFalse(EmptyMapServiceRegistry.has(PlainKey))
    }

    @Test
    fun `MapCapability の id は一意で fromId が引ける`() {
        val ids = MapCapability.entries.map { it.id }
        assertEquals("id が重複している", ids.size, ids.toSet().size)
        MapCapability.entries.forEach { assertSame(it, MapCapability.fromId(it.id)) }
        assertNull(MapCapability.fromId("nope"))
    }
}
