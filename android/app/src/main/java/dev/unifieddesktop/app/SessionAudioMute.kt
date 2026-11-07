package dev.unifieddesktop.app

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * B-131: PC-only audio while a session is live. A desktop session plays media through the
 * phone (the shell Browser's WebView) and the same audio is captured to the PC — the
 * phone's output must stay silent while that is happening, and the volume must come back
 * on every session end (clean exit, link death, session exception; the session pump's
 * `finally`).
 *
 * The capture path is unaffected by design: AudioPlaybackCapture taps the pre-speaker
 * stream, so a zeroed output volume changes only what the phone plays.
 *
 * Scope: STREAM_MUSIC only (never STREAM_RING / STREAM_ALARM), nothing persisted — the
 * saved volume lives in this object for the life of the process. A whole-process kill
 * while muted cannot restore by itself (nothing survived to restore from); the next
 * session start re-runs the same decisions and a session end always restores what THAT
 * process muted.
 *
 * HOW the silence is applied (phone-verified 2026-11-07): Android 14+ silently ignores
 * stream-volume changes from a background app (the 12:23 session muted fine because the
 * app was still foreground; the 12:51 restore and the 12:53 mute were both no-ops — the
 * call returned without an exception and the volume did not move). While the mute is
 * held, this object therefore keeps an ACTIVE [MediaSession] (the app genuinely is the
 * device's media pipeline during a session), which is the route media-volume controls
 * use from the background. Every step is VERIFIED with a read-back and logged; if a
 * mechanism does not take effect the ladder moves to the next one, and the last resort
 * is "stay unmuted" — never worse than the toggle OFF behaviour.
 *
 * Mechanism ladder at session start (pure parts unit-tested):
 *   1. INDEX — setStreamVolume(STREAM_MUSIC, 0); verified by getStreamVolume()==0.
 *      Restore is setStreamVolume(saved), verified the same way.
 *   2. MUTE FLAG — adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE); verified with
 *      isStreamMute(STREAM_MUSIC). Restore is ADJUST_UNMUTE; the index was never
 *      touched, so nothing else is needed.
 * The decisions are pure and unit-tested; [startSession]/[endSession] are the only
 * Android-side code. A hold is counted per accepted session so a trusted reconnect
 * replacing a live session never saves the muted 0 over the user's real volume.
 */
object SessionAudioMute {

    /** What a session start should do (pure, unit-tested). */
    data class Start(val mute: Boolean, val saved: Int, val holdsAfter: Int)

    /**
     * [enabled] is the Settings toggle; [current] is the STREAM_MUSIC volume now;
     * [alreadyMuted] is true when a previous session in this process still holds the mute.
     * A held mute is never re-saved (the saved value must stay the user's real pre-session
     * volume, never the muted 0), and the toggle being OFF never breaks an existing mute —
     * the session end restores it regardless of the toggle.
     */
    fun start(enabled: Boolean, current: Int, alreadyMuted: Boolean): Start = when {
        alreadyMuted -> Start(false, -1, 1)
        enabled -> Start(true, current, 1)
        else -> Start(false, -1, 0)
    }

    /** What a session end should do (pure, unit-tested). */
    data class End(val restore: Int?, val holdsAfter: Int)

    /**
     * [muted] is true while this process holds a mute; [saved] is the stored pre-session
     * volume; [anotherHolder] is true when a second live session still needs the silence.
     */
    fun end(muted: Boolean, saved: Int, anotherHolder: Boolean): End = when {
        !muted -> End(null, 0)
        anotherHolder -> End(null, 1)
        else -> End(saved.coerceAtLeast(0), 0)
    }

    /**
     * Which mechanism a start should use, given what a probe of each showed. Pure and
     * unit-tested: index first (an exact restore value), then the mute flag, then give up
     * honestly (stay unmuted).
     */
    enum class Mechanism { INDEX, MUTE_FLAG, NONE }

    fun chooseMechanism(indexWorked: Boolean, muteFlagWorked: Boolean): Mechanism = when {
        indexWorked -> Mechanism.INDEX
        muteFlagWorked -> Mechanism.MUTE_FLAG
        else -> Mechanism.NONE
    }

    private const val STREAM = AudioManager.STREAM_MUSIC
    private val lock = Any()
    private var holds = 0
    private var savedVolume = -1
    private var mechanism = Mechanism.NONE
    private var mediaSession: MediaSession? = null

    /** The Settings toggle (default ON per B-131). */
    internal fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getBoolean(PREFS_KEY, true)

    internal fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(PREFS_KEY, on).apply()
    }

    /**
     * An active MediaSession is what lets a background app move the media volume (the
     * route a Bluetooth headset's volume control uses). Created while the mute is held;
     * released on the last end. Null only when the platform refuses it.
     */
    private fun acquireMediaSession(ctx: Context): Boolean {
        if (mediaSession?.isActive == true) return true
        return try {
            val ms = MediaSession(ctx.applicationContext, "PopToPC session audio")
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build()
            )
            ms.isActive = true
            mediaSession = ms
            true
        } catch (t: Throwable) {
            android.util.Log.i("Phase4", "SESSIONAUDIO media session failed ${t.javaClass.simpleName}")
            false
        }
    }

    private fun releaseMediaSession() {
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Throwable) {}
        mediaSession = null
    }

    /** Called when the link service accepts a session (the LiveSession is registered). */
    fun startSession(ctx: Context, log: (String) -> Unit) {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        val on = enabled(ctx)
        synchronized(lock) {
            val current = try {
                audio.getStreamVolume(STREAM)
            } catch (t: Throwable) {
                log("SESSIONAUDIO start skip: no stream volume (${t.javaClass.simpleName})")
                return
            }
            val d = start(on, current, holds > 0)
            if (d.mute) {
                // A failed mute must leave no hold and no saved volume: the session end
                // would then "restore" a value it never muted away.
                acquireMediaSession(ctx)
                // Mechanism 1: exact index.
                var worked = try {
                    audio.setStreamVolume(STREAM, 0, 0)
                    audio.getStreamVolume(STREAM) == 0
                } catch (t: Throwable) {
                    log("SESSIONAUDIO index mute threw ${t.javaClass.simpleName}")
                    false
                }
                mechanism = chooseMechanism(worked, false)
                if (!worked) {
                    // Mechanism 2: the stream mute flag (the index is never touched).
                    worked = try {
                        audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_MUTE, 0)
                        audio.isStreamMute(STREAM)
                    } catch (t: Throwable) {
                        log("SESSIONAUDIO mute-flag threw ${t.javaClass.simpleName}")
                        false
                    }
                    mechanism = chooseMechanism(false, worked)
                }
                if (!worked) {
                    mechanism = Mechanism.NONE
                    releaseMediaSession()
                    log("SESSIONAUDIO start MUTE FAILED (background restriction?) - staying unmuted")
                    return
                }
                savedVolume = d.saved
            }
            holds = d.holdsAfter
            log(
                "SESSIONAUDIO start enabled=$on volume_before=${if (d.mute) d.saved else current} " +
                    "muted=${savedVolume >= 0} mechanism=$mechanism holds=$holds",
            )
        }
    }

    /** Called from the session pump's finally: every session end path restores. */
    fun endSession(ctx: Context, why: String, log: (String) -> Unit) {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        synchronized(lock) {
            if (holds > 0) holds--
            val d = end(savedVolume >= 0, savedVolume, holds > 0)
            if (d.restore != null) {
                var ok = false
                when (mechanism) {
                    Mechanism.INDEX -> ok = try {
                        audio.setStreamVolume(STREAM, d.restore, 0)
                        audio.getStreamVolume(STREAM) == d.restore
                    } catch (t: Throwable) {
                        log("SESSIONAUDIO index restore threw ${t.javaClass.simpleName}")
                        false
                    }
                    Mechanism.MUTE_FLAG -> ok = try {
                        audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
                        !audio.isStreamMute(STREAM)
                    } catch (t: Throwable) {
                        log("SESSIONAUDIO unmute threw ${t.javaClass.simpleName}")
                        false
                    }
                    Mechanism.NONE -> {}
                }
                if (!ok && mechanism == Mechanism.INDEX) {
                    // The index restore can be ignored from the background (measured
                    // 2026-11-07); unmute changes nothing the index path touched, so try it
                    // as the belt to the index's braces.
                    ok = try {
                        audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
                        !audio.isStreamMute(STREAM)
                    } catch (_: Throwable) {
                        false
                    }
                    if (ok) log("SESSIONAUDIO restore fell back to ADJUST_UNMUTE")
                }
                if (!ok) log("SESSIONAUDIO end RESTORE FAILED mechanism=$mechanism (volume stays ${d.restore} owed)")
                savedVolume = -1
            }
            if (holds == 0 && savedVolume < 0) releaseMediaSession()
            log("SESSIONAUDIO end why=$why mechanism=$mechanism restored=${d.restore ?: "-"} holds=$holds")
        }
    }

    /** Test/inspection view of the current state. */
    internal fun stateForTest(): Triple<Int, Int, Mechanism> =
        synchronized(lock) { Triple(holds, savedVolume, mechanism) }

    private const val PREFS_FILE = "phone-home"
    private const val PREFS_KEY = "mute_while_session"
}
