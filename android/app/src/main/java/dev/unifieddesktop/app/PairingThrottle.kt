package dev.unifieddesktop.app

/**
 * Audit B-144 (pure): any LAN host that can reach port 47600 can repeatedly open TLS
 * connections and pop the heads-up pairing prompt (nuisance; the prompt is
 * VISIBILITY_PUBLIC by the B-125 decision, so it is readable on the lock screen). The
 * phone now allows at most [MAX_PROMPTS] prompts per [WINDOW_MS]; further unpaired
 * connections inside the window are refused with a log line and a closed socket.
 *
 * The real user pairing flow is unaffected: one prompt per legitimate PC, and the
 * trusted-PC silent reconnect path never prompts at all.
 */
object PairingThrottle {
    const val WINDOW_MS = 60_000L
    const val MAX_PROMPTS = 5

    data class State(val windowStartMs: Long, val count: Int)

    /** @return (newState, allowed). `allowed=false` means refuse the prompt/connection. */
    fun allow(state: State, nowMs: Long): Pair<State, Boolean> {
        val s = if (nowMs - state.windowStartMs >= WINDOW_MS) State(nowMs, 0) else state
        return if (s.count < MAX_PROMPTS) {
            s.copy(count = s.count + 1) to true
        } else {
            s to false
        }
    }

    fun initial(): State = State(0L, 0)
}
