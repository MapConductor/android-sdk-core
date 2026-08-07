package com.mapconductor.core.map

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [MutableMapServiceRegistry] の意味論テスト。
 *
 * ios-sdk の `MutableMapServiceRegistry`（put / remove / clear / get）と同じ振る舞いに
 * 揃えてあることを確認する。特に [MutableMapServiceRegistry.remove] は
 * 「他の capability を残したまま1件だけ取り下げる」点が [MutableMapServiceRegistry.clear]
 * との違いなので、そこを明示的に押さえる。
 */
class MapServiceRegistryTest {
    private object KeyA : MapServiceKey<String>

    private object KeyB : MapServiceKey<String>

    @Test
    fun `put した値が get で取れる`() {
        val registry = MutableMapServiceRegistry()
        registry.put(KeyA, "a")
        assertSame("a", registry.get(KeyA))
    }

    @Test
    fun `未登録のキーは null`() {
        val registry = MutableMapServiceRegistry()
        assertNull(registry.get(KeyA))
    }

    @Test
    fun `put は同じキーを上書きする`() {
        val registry = MutableMapServiceRegistry()
        registry.put(KeyA, "first")
        registry.put(KeyA, "second")
        assertSame("second", registry.get(KeyA))
    }

    @Test
    fun `remove は指定したキーだけを取り消す`() {
        val registry = MutableMapServiceRegistry()
        registry.put(KeyA, "a")
        registry.put(KeyB, "b")

        registry.remove(KeyA)

        assertNull(registry.get(KeyA))
        assertSame("remove は他のキーに影響しないこと", "b", registry.get(KeyB))
    }

    @Test
    fun `未登録キーの remove は何も起こさない`() {
        val registry = MutableMapServiceRegistry()
        registry.put(KeyB, "b")

        registry.remove(KeyA)

        assertSame("b", registry.get(KeyB))
    }

    @Test
    fun `clear は全件を取り消す`() {
        val registry = MutableMapServiceRegistry()
        registry.put(KeyA, "a")
        registry.put(KeyB, "b")

        registry.clear()

        assertNull(registry.get(KeyA))
        assertNull(registry.get(KeyB))
    }

    @Test
    fun `EmptyMapServiceRegistry は常に null を返す`() {
        assertNull(EmptyMapServiceRegistry.get(KeyA))
    }
}
