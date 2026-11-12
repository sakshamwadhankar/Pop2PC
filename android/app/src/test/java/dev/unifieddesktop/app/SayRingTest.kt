package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T15-4 item 2 (B-139b, manager design): the link service's log ring. `say()` never does
 * disk I/O; lines accumulate in a capped ring and are flushed in one bulk write.
 */
class SayRingTest {

    @Test
    fun keeps_the_newest_capacity_lines_and_drops_the_oldest() {
        val r = SayRing(capacityLines = 5)
        for (i in 1..8) r.add("line$i", i.toLong() * 10)
        assertEquals(5, r.size())
        assertTrue(r.atCapacity)
        val text = r.drain()
        assertTrue(text.contains("line4"))
        assertTrue(text.contains("line8"))
        assertFalse("dropped oldest", text.contains("line1"))
        assertEquals(0, r.size())
    }

    @Test
    fun drain_leaves_the_ring_empty_and_lines_are_timestamped() {
        val r = SayRing()
        r.add("hello", 1234L)
        val text = r.drain()
        assertEquals("1234 hello\n", text)
        assertEquals(0, r.drain().length)
    }

    @Test
    fun periodic_flush_marker_fires_every_500_lines() {
        val r = SayRing()
        var fired = 0
        for (i in 1..1200) if (r.add("l$i", i.toLong())) fired++
        assertEquals(1200 / SayRing.FLUSH_EVERY.toInt(), fired)
        assertEquals(1200L, r.totalLines)
    }
}
