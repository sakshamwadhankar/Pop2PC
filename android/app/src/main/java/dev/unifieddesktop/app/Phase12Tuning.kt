package dev.unifieddesktop.app

/**
 * Phase 12 latency switches (B-051/B2). Every switch is off by default so a BEFORE run and an
 * AFTER run use the same build. The harness sets a named profile with
 * `phase4x p12lat:0|1|2|3`; encoder keys are read at the next video start, so the link is
 * restarted for an A/B run.
 *
 * Profiles (each one builds on 1):
 *   0  off (Phase 5 behaviour)
 *   1  A8: QTI slice spacing 4, one redraw 8 ms after a key, TCP_NODELAY
 *   2  1 plus slice spacing 1 (smaller slices) and an immediate redraw
 *   3  2 plus a second redraw 8 ms later (first one may still be the pre-key picture)
 *
 * Already on since Phase 5 and therefore not switched here: no B-frames, KEY_LOW_LATENCY,
 * Qualcomm low-latency vendor key, 60 fps pacing, 2-frame video queue cap, keyframe on
 * queue pressure, TCP_NODELAY on the PC side.
 */
object Phase12Tuning {
    /** Slice output on the encoder (smaller access units, less waiting for a full frame). */
    @Volatile var lowLatencyEncoder = false
        private set

    /** Queue redraw(s) right after a key is dispatched, so the flash frame leaves at once. */
    @Volatile var inputTriggeredFrame = false
        private set

    /** TCP_NODELAY on the phone's TLS socket (the PC already sets it). */
    @Volatile var tcpNoDelay = false
        private set

    /** Rows per QTI slice; 0 leaves the vendor key out. */
    @Volatile var sliceRows = 4
        private set

    /**
     * Audit A-18: intra-refresh probe. `KEY_INTRA_REFRESH_PERIOD` spreads an I-frame's cost
     * over many frames so one keyframe never lands whole in a frame. OFF by default: some
     * encoders list keys they then reject (B-056), and it is applied through the encoder's
     * optional-key drop ladder, so turning it on must be an A/B measurement on the phone
     * (harness `p12ir:<period-mbs>`, 0 = off), never a silent default.
     */
    @Volatile var intraRefreshPeriod = 0
        private set

    /**
     * T15-4 item 3: the adaptive-bitrate switch (audit row 25). Default ON - the policy
     * only reacts to the PC's measured `vq` stats and never exceeds the plan ceiling.
     * The live A/B (BEFORE/AFTER typing tables per rule zero) uses `p12abr:0|1`; if the
     * AFTER table is worse, the manager compares and the default flips.
     */
    @Volatile var abrOn = true // T15-4 item 3: shipped default; the live A/B tables are the manager gate
        private set

    /** Delay of the first input redraw; 8 ms lets the view draw the flash first. */
    @Volatile var inputRedrawDelayMs = 8
        private set

    /** Send a second redraw one 8 ms frame later (profile 3). */
    @Volatile var inputRedrawTwice = false
        private set

    /** Last profile applied, for logs and tests. */
    @Volatile var profile = 0
        private set

    fun setAll(on: Boolean) = setProfile(if (on) 1 else 0)

    fun setProfile(p: Int) {
        when (p) {
            0 -> apply(false, 4, 8, false)
            1 -> apply(true, 4, 8, false)
            2 -> apply(true, 1, 0, false)
            3 -> apply(true, 1, 0, true)
            else -> return
        }
        profile = p
    }

    /** Parse the harness argument. Unknown or out-of-range values change nothing. */
    fun configure(spec: String): Boolean {
        val p = spec.trim().toIntOrNull() ?: return false
        if (p !in 0..3) return false
        setProfile(p)
        return true
    }

    /**
     * Audit A-18: set the intra-refresh period in macroblocks per frame, or 0 to leave the
     * key out entirely (the default). Returns true when the value was accepted.
     */
    fun setIntraRefresh(periodMacroblocks: Int): Boolean {
        if (periodMacroblocks !in 0..100_000) return false
        intraRefreshPeriod = periodMacroblocks
        return true
    }

    /** T15-4 item 3: ABR on/off for the rule-zero A/B (`p12abr:0|1`). */
    fun setAbr(on: Boolean) {
        abrOn = on
    }

    private fun apply(encoder: Boolean, rows: Int, delayMs: Int, twice: Boolean) {
        lowLatencyEncoder = encoder
        inputTriggeredFrame = encoder
        tcpNoDelay = encoder
        sliceRows = rows
        inputRedrawDelayMs = delayMs
        inputRedrawTwice = twice
    }

    /**
     * Redraw delays for one key-down, relative to dispatch. Empty means no input-triggered
     * frame. Pure so the profile plan is testable without an encoder.
     */
    fun redrawDelaysMs(): IntArray {
        if (!inputTriggeredFrame) return IntArray(0)
        val first = inputRedrawDelayMs.coerceAtLeast(0)
        return if (inputRedrawTwice) intArrayOf(first, first + 8) else intArrayOf(first)
    }

    fun describe(): String =
        "P12TUNING profile=$profile encoder=$lowLatencyEncoder inputFrame=$inputTriggeredFrame " +
            "nodelay=$tcpNoDelay slices=$sliceRows redrawMs=${redrawDelaysMs().joinToString(",")}"
}
