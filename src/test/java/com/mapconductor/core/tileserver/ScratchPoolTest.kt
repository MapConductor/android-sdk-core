package com.mapconductor.core.tileserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScratchPoolTest {
    @Test
    fun `holds no more than its capacity, however many threads used it`() {
        // A thread-local kept one per thread, and map SDK threads that never stop
        // (Google's tile-overlay pool, three per map) kept theirs for good.
        val pool = ScratchPool<ByteArray>(capacity = 2)
        val released = (1..10).map { pool.release(ByteArray(1)) }

        assertEquals(listOf(true, true) + List(8) { false }, released)
        assertEquals(2, pool.size)
    }

    @Test
    fun `hands back what was released`() {
        val pool = ScratchPool<ByteArray>(capacity = 2)
        val item = ByteArray(4)
        pool.release(item)

        assertSame(item, pool.acquire())
        assertNull(pool.acquire())
    }

    @Test
    fun `drops pooled objects the caller cannot use`() {
        val pool = ScratchPool<ByteArray>(capacity = 2)
        pool.release(ByteArray(4))

        assertNull(pool.acquire { it.size >= 8 })
        assertEquals(0, pool.size)
        assertTrue(pool.release(ByteArray(8)))
        assertFalse(pool.acquire { it.size >= 8 } == null)
    }
}
