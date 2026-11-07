package app.grapheneos.camera.thmc

import org.junit.Assert.*
import org.junit.Test

class PoolLedgerTest {

    @Test fun take_hit_and_miss() {
        val p = PoolLedger(1000)
        assertFalse(p.take(100))
        assertEquals(1L, p.misses)
        p.add(100, 100)
        assertTrue(p.take(100))
        assertEquals(1L, p.hits)
        assertEquals(0L, p.currentBytes)
        assertFalse(p.take(100))
    }

    @Test fun evicts_oldest_first() {
        val p = PoolLedger(300)
        p.add(1, 100)
        p.add(2, 100)
        val ev = p.add(3, 100)
        assertTrue(ev.isEmpty())
        assertEquals(300L, p.currentBytes)
        val ev2 = p.add(4, 100)
        assertEquals(listOf(1L), ev2) // самый старый
        assertEquals(300L, p.currentBytes)
        assertEquals(3, p.buckets())
    }

    @Test fun too_big_rejected() {
        val p = PoolLedger(100)
        assertEquals(listOf(999L), p.add(999, 500))
        assertEquals(0L, p.currentBytes)
        assertEquals(0, p.buckets())
    }

    @Test fun keyFor_argb8888_bytes() {
        val p = PoolLedger(1 shl 30)
        assertEquals(2048L * 1536 * 4, p.keyFor(2048, 1536))
    }

    @Test fun clear_resets() {
        val p = PoolLedger(1000)
        p.add(1, 100)
        p.clear()
        assertEquals(0L, p.currentBytes)
        assertEquals(0, p.buckets())
    }
}
