package dev.unifieddesktop.app

/**
 * B-008 decisions (T15-2 + owner direction 2026-11-13): the phone screen goes black while a
 * guest runs because the guest's placeholder window sits TOP on display 0. Two engine-level
 * attempts came first, both measured:
 *
 * (b) DISPLAY-FIRST: ask the engine to start the guest's stub directly on the window's own
 *     private display (the engine now forwards launch options to the real AMS - see the
 *     ActivityStack patch in engine/fetch-source.sh). If the platform allows it, display 0
 *     never hosts the guest task and the problem is gone. AOSP ActivityTaskSupervisor
 *     (read 2026-11-08) denies an app-made display's first launch unless the activity is
 *     allowEmbedded AND the caller has ACTIVITY_EMBEDDING or already has an activity of its
 *     uid on that display - so a denial is the expected outcome; the caller then falls back.
 * (a) HOME-TO-FRONT: on the proven B1 (decor-move) path, bring the PopToPC home activity's
 *     task to the front OVER the guest instead of moving the guest away.
 *
 *  MEASURED 2026-11-09 (T15-2B), phone 2c0f6edc: (a) FAILS. As coded, the startActivity runs
 *  on the window's display-49 context, so AMS attached launchDisplayId=49 (a private,
 *  untrusted display) and denied it: "Permission Denial ... launchDisplayId=49" (B008A log
 *  line 00:42:53.251); the phone stayed black at +8 s/+20 s while the PC kept the guest.
 *  Forced onto display 0 with the same reorder flags (adb am start --display 0), the home did
 *  come to the front, but the guest stopped and the PC's Android window went black by +8 s
 *  and stayed black at +20 s - the same outcome T15-1 measured for moveTaskToBack. So
 *  HOME_TO_FRONT stays OFF.
 *
 * (c) TRANSLUCENT GUEST WINDOW (the fix, owner direction 2026-11-13): the root cause was
 *     lifecycle, not task order. An alpha-0 window is still OPAQUE to the window manager,
 *     so display 0 treated everything behind the guest as invisible - the home behind was
 *     STOPPED and the phone composited black. Making the guest window TRANSLUCENT
 *     (`Window.setFormat(TRANSLUCENT)` + `Activity.setTranslucent(true)` + a transparent
 *     background, all public APIs applied before super.onCreate in
 *     GuestPresentationHost.beforeMainActivityOnCreate) keeps the activity behind STARTED
 *     (only onPause, never onStop) so the phone keeps showing real content, while the guest
 *     itself stays RESUMED (still the front task) and its Presentation on the private
 *     display keeps rendering - the exact failure that killed (a) never happens, because
 *     nothing covers the guest. Verified against the documented translucent-activity
 *     lifecycle rule (stackoverflow.com/questions/51536494;
 *     developer.android.com/guide/components/activities/tasks-and-back-stack) and against
 *     the emulator before/after screenshots (B-008 round, 2026-11-13).
 *
 * Everything here is a pure decision so the unit suite can pin it without a device.
 */
object B008Policy {
    /** Attempt (b) is tried before the B1 decor-move launch. */
    const val DISPLAY_FIRST = true

    /** Attempt (a) is tried after a successful B1 launch (never after a working (b)).
     *  OFF since 2026-11-09: see the class comment - the measured attempt killed the guest's
     *  PC stream. Turning this back on needs a new mechanism, not a retry. */
    const val HOME_TO_FRONT = false

    /**
     * Attempt (c): the guest window must be translucent (see the class comment). ON: it is
     * the B-008 fix and applies to every guest activity through
     * GuestPresentationHost.beforeMainActivityOnCreate.
     */
    const val GUEST_WINDOW_TRANSLUCENT = true

    /** T15-1's +6 s move showed both sides working at +8 s and PC black at +20 s; the same
     *  delay makes the home-to-front probe directly comparable. */
    const val HOME_TO_FRONT_DELAY_MS = 6000L

    /** Flags for bringing the host home task over the guest task. */
    const val HOME_TO_FRONT_FLAGS =
        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT

    /**
     * Pure: should the engine be asked for a direct private-display launch? Only for a real
     * imported guest on an actual virtual display (id > 0); the host's own UI never goes
     * through this.
     */
    fun displayFirst(displayId: Int, imported: Boolean): Boolean =
        DISPLAY_FIRST && imported && displayId > 0

    /**
     * Pure: should the home be brought to the front over the guest? Only on the B1 path (a
     * working direct launch already keeps display 0 clean) and only for a launch that
     * actually started the guest. Always false while HOME_TO_FRONT is off (measured failure).
     */
    fun homeToFront(directLaunchWorked: Boolean, guestLaunched: Boolean): Boolean =
        HOME_TO_FRONT && !directLaunchWorked && guestLaunched
}
