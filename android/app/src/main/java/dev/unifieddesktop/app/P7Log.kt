package dev.unifieddesktop.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase-7 log that survives the phone's fast logcat rotation: every line goes to logcat and,
 * on DEBUG builds only, is appended to the app-private `files/p7.log` (read with
 * `run-as dev.unifieddesktop.app`).
 *
 * Audit B-139b: P7Log sits on hot paths (every shell click, every X11 key, guest logs), so
 * release builds never touch the filesystem or build the stamp string here; the phone
 * evidence the coder needs still comes from debug builds.
 */
object P7Log {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun say(ctx: Context, line: String) {
        android.util.Log.i("Phase7", line)
        if (!BuildConfig.DEBUG) return
        try {
            val stamped = fmt.format(Date()) + " " + line
            File(ctx.filesDir, "p7.log").appendText(stamped + "\n")
        } catch (_: Throwable) {
            // A log line must never break the caller.
        }
    }
}
