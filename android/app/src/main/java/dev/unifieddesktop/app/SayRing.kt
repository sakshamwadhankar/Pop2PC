package dev.unifieddesktop.app

/**
 * T15-4 item 2 (audit B-139b, manager design): the link service's log lines land in an
 * in-memory ring and are flushed to `files/p4/p4.log` in ONE bulk write at session end
 * (and every [FLUSH_EVERY] lines on debug builds, so an overnight run that crashes still
 * leaves evidence). `say()` therefore never does disk I/O on the input/draw path.
 *
 * Pure (no Android imports) so the cap/order behaviour is unit-tested in SayRingTest.
 */
class SayRing(private val capacityLines: Int = 2_000) {
    private val lines = ArrayDeque<String>(capacityLines)
    var totalLines = 0L
        private set

    /** Append one line; the oldest is dropped once the ring is full. */
    fun add(line: String, timestampMs: Long): Boolean {
        totalLines++
        if (lines.size >= capacityLines) lines.removeFirst()
        lines.addLast("${timestampMs} $line")
        return totalLines % FLUSH_EVERY == 0L
    }

    /** True when the ring is at capacity (the caller may flush; never mandatory). */
    val atCapacity: Boolean get() = lines.size >= capacityLines

    /** Drain the ring newest-last, one timestamped line per row, and leave it empty. */
    fun drain(): String {
        val out = StringBuilder()
        for (l in lines) out.append(l).append('\n')
        lines.clear()
        return out.toString()
    }

    fun size(): Int = lines.size

    companion object {
        /** Debug builds also flush on this many new lines (crash evidence, rare enough). */
        const val FLUSH_EVERY = 500L
    }
}
