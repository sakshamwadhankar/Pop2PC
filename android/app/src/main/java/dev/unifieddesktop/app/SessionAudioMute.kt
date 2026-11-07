package dev.unifieddesktop.app

import android.app.NotificationChannel
import android.app.NotificationManager
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
 *
 * B-136 (2026-11-07): the background restore was silently ignored once and the old
 * INDEX->ADJUST_UNMUTE fallback claimed success from a mute flag that was never set.
 * Since this fix: (a) an INDEX restore is verified by the index read-back ONLY; (b) an
 * unverified restore is RECORDED as the owed volume instead of being dropped; (c)
 * [restoreOwedOnForeground] (HomeActivity.onResume) finishes an owed restore the moment
 * the app is foreground again, and the next session start clears a stale one first; (d)
 * the held MediaSession is bound to an ongoing MediaStyle notification, which raises its
 * priority so background media-volume commands are honoured.
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

    // ---- B-136: restore verification and owed-volume bookkeeping (pure, unit-tested) ----

    /**
     * B-136 (a): a restore is VERIFIED by the stream-volume INDEX read-back only. The
     * ADJUST_UNMUTE flag check is never used to claim a restore: when no mute flag was set
     * (the INDEX mechanism) `isStreamMute` is false all along, so the old fallback "checked"
     * a flag that proved nothing and claimed success while the volume stayed 0 (measured
     * 2026-11-07, 15:06). The one legitimate flag check is the MUTE_FLAG mechanism's own
     * state, where the flag was genuinely set at start and the index never moved - there
     * the index read-back cannot distinguish anything, so the flag IS the state to verify.
     */
    fun restoreVerified(indexReadBack: Int, wanted: Int): Boolean = indexReadBack == wanted

    /** B-136 (b): what the owed-volume state becomes after one restore attempt. */
    fun owedAfterRestore(verified: Boolean, wanted: Int, owedBefore: Int): Int = when {
        !verified -> wanted
        // A verified restore of THIS session's value must not erase an OLDER owed volume:
        // when the start could not clear the owed 60 in the background it saved the 0 it
        // found, so this session's end "restores" 0 - the user's real 60 is still owed.
        owedBefore >= 0 && owedBefore != wanted -> owedBefore
        else -> -1
    }

    /**
     * B-136 (c): the app coming to the foreground must finish an owed restore - but only
     * when no session is live (a live session's silence is intentional, not owed).
     */
    fun needsForegroundRestore(owed: Int, holdingSessions: Int): Boolean =
        owed >= 0 && holdingSessions == 0

    private const val STREAM = AudioManager.STREAM_MUSIC
    private val lock = Any()
    private var holds = 0
    private var savedVolume = -1
    /** B-136 (b): a restore whose read-back did not verify; the foreground retry owes this. */
    private var owedVolume = -1
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
        if (mediaSession?.isActive == true) {
            postSessionNotification(ctx)
            return true
        }
        return try {
            val ms = MediaSession(ctx.applicationContext, "PopToPC session audio")
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build()
            )
            ms.isActive = true
            mediaSession = ms
            // B-136 (d): the MediaStyle notification bound to this session is what raises
            // its priority above "recent" - background media-volume commands are honoured
            // for the session a notification is showing. The service's own foreground
            // notification (47600) and the pairing prompt (47611) are untouched.
            postSessionNotification(ctx)
            true
        } catch (t: Throwable) {
            android.util.Log.i("Phase4", "SESSIONAUDIO media session failed ${t.javaClass.simpleName}")
            false
        }
    }

    /**
     * B-136 (d): the ongoing MediaStyle notification on the service's existing low-importance
     * session channel. Without POST_NOTIFICATIONS (the Android 13+ runtime prompt the user
     * answered at first run) notify() is a no-op - the bare active MediaSession stays as the
     * lower-priority fallback, never worse than the toggle OFF behaviour.
     */
    private fun postSessionNotification(ctx: Context) {
        val ms = mediaSession ?: return
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    SESSION_CHANNEL,
                    "PC session",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
            val n = androidx.core.app.NotificationCompat.Builder(ctx, SESSION_CHANNEL)
                .setSmallIcon(dev.unifieddesktop.app.R.drawable.ic_stat_desktop)
                .setContentTitle("PopToPC session")
                .setContentText("Phone audio muted while the PC session is live")
                .setOngoing(true)
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        // MediaStyle binds the compat token (a wrapper of the platform one);
                        // androidx.media 1.7.0 keeps this class in the v4 package.
                        .setMediaSession(
                            android.support.v4.media.session.MediaSessionCompat.Token
                                .fromToken(ms.sessionToken)
                        )
                )
                .build()
            nm.notify(AUDIO_NOTIF_ID, n)
        } catch (t: Throwable) {
            android.util.Log.i("Phase4", "SESSIONAUDIO notification failed ${t.javaClass.simpleName}")
        }
    }

    private fun cancelSessionNotification(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(AUDIO_NOTIF_ID)
        } catch (_: Throwable) {}
    }

    private fun releaseMediaSession(ctx: Context) {
        cancelSessionNotification(ctx)
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
            // B-136 (b): clear a previous session's owed restore FIRST, so the volume read
            // below is the user's real volume and not the still-muted 0 (the accepted
            // "next session saves the volume it finds" residual, closed where it can be).
            if (needsForegroundRestore(owedVolume, holds)) {
                val owed = owedVolume
                val ok = try {
                    audio.setStreamVolume(STREAM, owed, 0)
                    restoreVerified(audio.getStreamVolume(STREAM), owed)
                } catch (t: Throwable) {
                    log("SESSIONAUDIO start owed restore threw ${t.javaClass.simpleName}")
                    false
                }
                owedVolume = owedAfterRestore(ok, owed, owedVolume)
                if (ok) {
                    savedVolume = -1
                    log("SESSIONAUDIO start owed restore applied volume=$owed")
                } else {
                    log("SESSIONAUDIO start owed restore did NOT verify (owed=$owed stays)")
                }
            }
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
                    releaseMediaSession(ctx)
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
                val wanted = d.restore
                var ok = false
                when (mechanism) {
                    Mechanism.INDEX -> ok = try {
                        audio.setStreamVolume(STREAM, wanted, 0)
                        // B-136 (a): verified by the INDEX read-back only. No ADJUST_UNMUTE
                        // fallback here: the flag was never set, so `isStreamMute` proves
                        // nothing - the old fallback claimed success while the volume
                        // stayed 0 (B-136, measured 2026-11-07 15:06).
                        restoreVerified(audio.getStreamVolume(STREAM), wanted)
                    } catch (t: Throwable) {
                        log("SESSIONAUDIO index restore threw ${t.javaClass.simpleName}")
                        false
                    }
                    Mechanism.MUTE_FLAG -> ok = try {
                        audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
                        // The flag IS this mechanism's state (it was set at start; the index
                        // never moved, so a read-back cannot distinguish anything).
                        !audio.isStreamMute(STREAM)
                    } catch (t: Throwable) {
                        log("SESSIONAUDIO unmute threw ${t.javaClass.simpleName}")
                        false
                    }
                    Mechanism.NONE -> {}
                }
                // B-136 (b): an unverified restore is RECORDED, never dropped - the app
                // coming to the foreground retries it (restoreOwedOnForeground).
                owedVolume = owedAfterRestore(ok, wanted, owedVolume)
                if (!ok) {
                    log("SESSIONAUDIO end RESTORE NOT VERIFIED mechanism=$mechanism (volume $wanted OWED)")
                }
                if (ok) savedVolume = -1
            }
            if (holds == 0 && savedVolume < 0 && owedVolume < 0) releaseMediaSession(ctx)
            log(
                "SESSIONAUDIO end why=$why mechanism=$mechanism restored=${d.restore ?: "-"} " +
                    "verified=${d.restore == null || owedVolume < 0} owed=$owedVolume holds=$holds",
            )
        }
    }

    /**
     * B-136 (c): HomeActivity.onResume. A background restore can be silently ignored by
     * Android (measured 2026-11-07); when the user opens the app the restriction does not
     * apply, so a still-owed volume is restored here and verified by read-back. Kept owed
     * when even this fails - the next session start and the next foreground retry again.
     */
    fun restoreOwedOnForeground(ctx: Context) {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        synchronized(lock) {
            if (!needsForegroundRestore(owedVolume, holds)) return
            val owed = owedVolume
            val ok = try {
                audio.setStreamVolume(STREAM, owed, 0)
                restoreVerified(audio.getStreamVolume(STREAM), owed)
            } catch (t: Throwable) {
                android.util.Log.i(
                    "Phase4",
                    "SESSIONAUDIO foreground restore threw ${t.javaClass.simpleName}",
                )
                false
            }
            owedVolume = owedAfterRestore(ok, owed, owedVolume)
            if (ok) {
                savedVolume = -1
                if (holds == 0) releaseMediaSession(ctx)
            }
            android.util.Log.i(
                "Phase4",
                "SESSIONAUDIO foreground restore owed=$owed verified=$ok (owed stays $owedVolume)",
            )
        }
    }

    /** Test/inspection view of the current state. */
    internal fun stateForTest(): Triple<Int, Int, Mechanism> =
        synchronized(lock) { Triple(holds, savedVolume, mechanism) }

    private const val PREFS_FILE = "phone-home"
    private const val PREFS_KEY = "mute_while_session"

    /** The service's own low-importance session channel; the audio notification rides it. */
    private const val SESSION_CHANNEL = "ud_p4_session"

    /** Distinct from the service foreground id (47600) and the pairing prompt (47611). */
    private const val AUDIO_NOTIF_ID = 47612
}
