package dev.unifieddesktop.app

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-132: the GeckoView browser engine decisions (pure halves). The WebView fallback must be
 * automatic, sticky and explainable; the key routing reuses [KeyRoute] exactly (T13-9).
 */
class Phase14BrowserPolicyTest {

    // ---- engine (the fallback decision) --------------------------------------

    @Test
    fun arm64_device_uses_gecko() {
        val d = Phase14BrowserPolicy.engine(arm64 = true)
        assertTrue(d.useGecko)
        assertEquals("arm64-v8a present", d.reason)
    }

    @Test
    fun non_arm64_builds_fall_back_to_the_webview() {
        // Only the arm64-v8a artifact ships (AAB < 200 MB); the emulator's x86_64 build
        // must run the WebView engine instead of crashing.
        val d = Phase14BrowserPolicy.engine(arm64 = false)
        assertFalse(d.useGecko)
        assertTrue(d.reason.contains("arm64"))
    }

    @Test
    fun a_failed_gecko_init_is_sticky_for_the_process() {
        // One broken init must not thrash between engines on every window open.
        val d = Phase14BrowserPolicy.engine(arm64 = true, initFailed = true)
        assertFalse(d.useGecko)
        assertTrue(d.reason.contains("failed"))
    }

    @Test
    fun arm64_detection_accepts_the_real_abi_list_and_rejects_others() {
        assertTrue(Phase14BrowserPolicy.isArm64(listOf("arm64-v8a", "armeabi-v7a")))
        assertTrue(Phase14BrowserPolicy.isArm64(listOf("armeabi-v7a", "arm64-v8a")))
        assertFalse(Phase14BrowserPolicy.isArm64(listOf("x86_64", "x86")))
        assertFalse(Phase14BrowserPolicy.isArm64(emptyList()))
        // A device may report a 16 KB variant suffix; the exact artifact ABI must match.
        assertFalse(Phase14BrowserPolicy.isArm64(listOf("arm64-v8a-16k")))
    }

    // ---- runtime fallback (child-process crash) -------------------------------

    @Test
    fun the_first_content_crash_triggers_the_fallback() {
        assertTrue(Phase14BrowserPolicy.fallbackAfterCrash(1))
        assertTrue(Phase14BrowserPolicy.fallbackAfterCrash(3))
    }

    @Test
    fun no_crash_means_no_fallback() {
        assertFalse(Phase14BrowserPolicy.fallbackAfterCrash(0))
    }

    @Test
    fun a_load_that_never_painted_times_out_into_the_fallback() {
        assertTrue(
            Phase14BrowserPolicy.fallbackAfterLoadTimeout(
                painted = false, waitedMs = 25_000, timeoutMs = 25_000,
            )
        )
        // A painted page (or a still-waiting load) never falls back.
        assertFalse(
            Phase14BrowserPolicy.fallbackAfterLoadTimeout(
                painted = true, waitedMs = 60_000, timeoutMs = 25_000,
            )
        )
        assertFalse(
            Phase14BrowserPolicy.fallbackAfterLoadTimeout(
                painted = false, waitedMs = 10_000, timeoutMs = 25_000,
            )
        )
    }

    // ---- profile --------------------------------------------------------------

    @Test
    fun the_profile_is_persistent_exactly_when_not_private() {
        assertTrue(Phase14BrowserPolicy.profilePersisted(privateMode = false))
        assertFalse(Phase14BrowserPolicy.profilePersisted(privateMode = true))
    }

    // ---- key routing (the T13-9 reuse) ----------------------------------------

    @Test
    fun gecko_keys_take_the_same_routes_as_the_webview() {
        val keyWebView = KeyRoute.decide(
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, -1, 'a',
        )
        // The Gecko view calls the SAME KeyRoute.decide; pin the decisions it must show.
        assertEquals(KeyRoute.Route.COMMIT, keyWebView) // printable -> IME route
        assertEquals(
            KeyRoute.Route.RAW,
            KeyRoute.decide(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON, -1, 'a'),
        ) // Ctrl+A stays a real key event
        assertEquals(
            KeyRoute.Route.SWALLOW,
            KeyRoute.decide(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, KeyEvent.KEYCODE_A, 'a'),
        ) // the UP of a committed key is swallowed
    }

    @Test
    fun the_click_rebind_rule_is_the_t13_9_one() {
        assertTrue(ImeRebind.afterClick(MotionEvent.ACTION_UP))
        assertFalse(ImeRebind.afterClick(MotionEvent.ACTION_DOWN))
        assertFalse(ImeRebind.afterClick(MotionEvent.ACTION_MOVE))
    }

    // ---- T14-3 item 4: the fallback WebView's robot-wall posture ----------------------

    /** The fallback UA matches the DEVICE WebView major version (a mismatch is a bot signal). */
    @Test
    fun fallback_ua_matches_the_device_webview_major() {
        val ua = Phase14BrowserPolicy.desktopChromeUa("140.0.7430.61")
        assertTrue("UA must claim the device WebView major 140, got: $ua", ua.contains("Chrome/140.0.0.0"))
        assertTrue(ua.startsWith("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"))
    }

    @Test
    fun fallback_ua_falls_back_to_a_sane_major_when_unreadable() {
        assertTrue(Phase14BrowserPolicy.desktopChromeUa(null).contains("Chrome/131.0.0.0"))
        assertTrue(Phase14BrowserPolicy.desktopChromeUa("garbage").contains("Chrome/131.0.0.0"))
        assertTrue(Phase14BrowserPolicy.desktopChromeUa("0.1.2.3").contains("Chrome/131.0.0.0"))
        assertTrue(Phase14BrowserPolicy.desktopChromeUa("  145.0  ").contains("Chrome/145.0.0.0"))
    }
}
