package dev.unifieddesktop.app

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * B-132: the desktop Browser's engine. GeckoView (real Firefox, MPL-2.0, THIRD_PARTY.md)
 * replaces the WebView for the user-facing Browser so sites stop fingerprinting a
 * cookie-less embedded WebView as a bot. The WebView stays as the AUTOMATIC fallback when
 * GeckoView cannot init at runtime (and on builds without the arm64 artifact).
 *
 * The pure decisions live in [Phase14BrowserPolicy] (unit-tested); this file holds the
 * runtime holder and the input view. The session/delegate wiring is in [BrowserView],
 * which owns the tab model, the address bar and the status line.
 */
internal object Phase14BrowserPolicy {

    /** The engine the Browser uses, and why (the fallback decision, unit-tested). */
    data class EngineDecision(val useGecko: Boolean, val reason: String)

    /** Only arm64-v8a ships the GeckoView artifact (AAB < 200 MB); see THIRD_PARTY.md. */
    fun isArm64(abis: List<String>): Boolean = abis.any { it == "arm64-v8a" }

    /**
     * [arm64]: the device can load the artifact at all. [initFailed]: a GeckoView init in
     * this process already threw once - the fallback is sticky for the process so a broken
     * engine cannot thrash between engines on every window open.
     */
    fun engine(arm64: Boolean, initFailed: Boolean = false): EngineDecision = when {
        initFailed -> EngineDecision(false, "gecko init failed earlier in this process")
        !arm64 -> EngineDecision(false, "no arm64-v8a ABI (artifact not shipped)")
        else -> EngineDecision(true, "arm64-v8a present")
    }

    /**
     * The runtime fallback: GeckoView's child processes can die at RUNTIME (an OEM skin's
     * ActivityThread NPEs on every geckoview child bind - found on the test phone's ColorOS
     * 2026-11-07; Google issuetracker 460828007 is the same shape). The session's onCrash
     * fires per crash; after [crashCount] reaches [threshold] the window swaps to the
     * WebView engine for its remaining life and the process refuses Gecko afterwards.
     */
    fun fallbackAfterCrash(crashCount: Int, threshold: Int = 1): Boolean =
        crashCount >= threshold && threshold > 0

    /**
     * Second runtime trigger (this phone's exact failure): the children die at BIND, before
     * Gecko associates the crash with a session, so onCrash never fires and the page sits
     * in "loading" forever. A load that started but painted nothing within [timeoutMs]
     * falls back the same way. Pure and unit-tested.
     */
    fun fallbackAfterLoadTimeout(painted: Boolean, waitedMs: Long, timeoutMs: Long): Boolean =
        !painted && waitedMs >= timeoutMs && timeoutMs > 0

    /** The profile is persistent exactly when the session is not private (unit-tested). */
    fun profilePersisted(privateMode: Boolean): Boolean = !privateMode
}

/**
 * One GeckoRuntime per process (GeckoRuntime.create throws on the second call). The default
 * profile is persistent: cookies, logins and storage survive app restarts, which is the
 * B-132 requirement the WebView could not meet.
 */
internal object GeckoEngine {
    private const val TAG = "Phase6"
    @Volatile private var runtime: GeckoRuntime? = null

    /** Sticky per-process marker: set by the runtime-crash fallback (B-132). */
    @Volatile var initFailed = false

    /** The process-wide runtime, or null when creation failed (the caller falls back). */
    fun runtime(ctx: Context): GeckoRuntime? = synchronized(this) {
        if (initFailed) return null
        runtime?.let { return it }
        try {
            GeckoRuntime.create(
                ctx.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .consoleOutput(false)
                    .debugLogging(false)
                    .build(),
            ).also {
                runtime = it
                android.util.Log.i(TAG, "B132 GeckoRuntime created")
            }
        } catch (t: Throwable) {
            android.util.Log.i(
                TAG,
                "B132 GeckoRuntime.create failed ${t.javaClass.simpleName}: ${t.message?.take(80)}",
            )
            null
        }
    }
}

/**
 * The KeyWebView analogue for Gecko: reports window focus like [KeyWebView] (the desktop
 * input path sends keys only into a focused view), rebinds the IME after a click with the
 * T13-9 [ImeRebind] restartInput pattern, and routes PC keys through the same [KeyRoute]
 * decision as the WebView: printable characters take the IME route, everything else stays
 * a real key event for shortcuts.
 */
class GeckoKeyView(context: Context) : org.mozilla.geckoview.GeckoView(context) {

    /** The keycode whose down went through the input connection, so its up is swallowed. */
    private var lastCommitted = -1

    override fun hasWindowFocus(): Boolean = true

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(true)
        isFocusable = true
        isFocusableInTouchMode = true
        if (!isFocused) requestFocus()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        // T13-9 pattern (B-128): a click moves the renderer's DOM focus; the IME attachment
        // follows the old editable until restartInput drops it. Gecko manages its own input
        // connection, so the rebind is what re-attaches it to the editable focused NOW.
        if (ImeRebind.afterClick(event.actionMasked)) {
            context.getSystemService(InputMethodManager::class.java)?.let { imm ->
                imm.restartInput(this)
                android.util.Log.i("Phase6", "B132 rebind after click")
            }
        }
        return handled
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ch = Phase5Protocol.keyCodeToChar(event.keyCode, event.metaState)
        when (KeyRoute.decide(event.action, event.keyCode, event.metaState, lastCommitted, ch)) {
            KeyRoute.Route.SWALLOW -> {
                lastCommitted = -1
                return true
            }
            KeyRoute.Route.COMMIT -> {
                // Gecko's own input connection (it implements onCreateInputConnection for
                // its soft keyboard). Unlike Chromium's EditContext problem (B-039/B-128),
                // Gecko also inserts raw keydowns correctly - so when no connection is
                // available the raw event is safe to let through.
                val ic = super.onCreateInputConnection(EditorInfo())
                if (ic != null && ic.commitText(ch.toString(), 1)) {
                    lastCommitted = event.keyCode
                    android.util.Log.i("Phase6", "B132 route=ime ch='$ch'")
                    return true
                }
                lastCommitted = -1
                return super.dispatchKeyEvent(event)
            }
            KeyRoute.Route.RAW -> return super.dispatchKeyEvent(event)
        }
    }
}
