package dev.unifieddesktop.app

import android.content.Context

/**
 * B3/Phase 12: one guarded path to "code-server is up", shared by the VS Code window and the
 * optional desktop pre-warm. `@Synchronized` matters: a user can click VS Code while the
 * pre-warm thread is still starting the server, and two `Phase3Server.start` calls would race
 * for the same session file and port.
 *
 * B-108: the server now requires its per-start password. This returns the login cookie the
 * WebView must have before its first load (fresh login for a reused server, the start's own
 * login for a new one).
 */
object CodeServerWarm {
    data class Result(
        val started: Boolean,
        val ok: Boolean,
        val port: Int,
        val waitMs: Long,
        val cookie: String? = null,
    )

    @Synchronized
    fun ensureRunning(
        context: Context,
        timeoutMs: Long = CodeServerGate.MAX_MS,
        statusCallback: ((String) -> Unit)? = null,
    ): Result {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val sf = Phase3Server.sessionFile(context)
        var port = -1
        var code = 0
        if (sf.isFile) {
            sf.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("port=")) {
                        port = line.substringAfter('=').trim().toIntOrNull() ?: -1
                    }
                }
            }
            if (port > 0) code = Phase3Server.healthz(port).first
        }
        P7Log.say(context, "VSCODE gate session=${sf.isFile} port=$port healthz=$code")
        if (!CodeServerGate.needsStart(sf.isFile && port > 0, code)) {
            // Healthy server from an earlier start: mint a fresh cookie for our WebView.
            val cookie = Phase3Server.cookieFor(context, port)
            P7Log.say(context, "VSCODE reuse port=$port login=${if (cookie != null) "ok" else "FAIL"}")
            return Result(false, cookie != null, port,
                android.os.SystemClock.elapsedRealtime() - t0, cookie)
        }
        // If code-server is not yet staged (fresh install: entry.js missing), fetch it
        // first. This is the common case on a real user's first VS Code open. The ~117 MB
        // deb is downloaded with the T14-3 resume mechanism (Range/206, kept partial).
        val entry = java.io.File(Phase3Data.root(context), "out/node/entry.js")
        if (!entry.isFile) {
            P7Log.say(context, "VSCODE entry missing — fetching code-server now")
            // T15-1: report live progress (percent of the pinned deb against its known
            // byte count, in MB) so a first-run staging fetch never looks frozen. Throttled
            // to the integer percent so a 16 KiB chunk per post does not flood the UI thread.
            val pinBytes = Phase3Data.pinForDevice()?.bytes ?: -1L
            var lastKey = -1
            fun report(bytes: Long, total: Long) {
                // Prefer the size the server promised (the 206 Content-Range total on a
                // resumed fetch); fall back to the pinned deb size. One post per whole
                // percent (per MB when the size is unknown), never one per 16 KiB chunk.
                val full = if (total > 0) total else pinBytes
                val key = if (full > 0) (100.0 * bytes / full).toInt().coerceIn(0, 100)
                    else (bytes / (1 shl 20)).toInt()
                if (key != lastKey) {
                    lastKey = key
                    statusCallback?.invoke(ProgressText.line("Downloading VS Code", bytes, full))
                }
            }
            statusCallback?.invoke(ProgressText.line("Downloading VS Code", 0L, pinBytes))
            try {
                Phase3Data.fetch(context, progress = ::report)
            } catch (t: Throwable) {
                P7Log.say(context, "VSCODE fetch FAILED $t")
                return Result(true, false, -1, android.os.SystemClock.elapsedRealtime() - t0)
            }
            if (!entry.isFile) {
                P7Log.say(context, "VSCODE entry still missing after fetch — network incomplete?")
                return Result(true, false, -1, android.os.SystemClock.elapsedRealtime() - t0)
            }
            P7Log.say(context, "VSCODE fetch done, entry present, starting code-server")
            statusCallback?.invoke("Installing VS Code... (unpacking 120 MB)")
        }
        P7Log.say(context, "VSCODE starting code-server (wait up to $timeoutMs ms)")
        val session = Phase3Server.start(context, timeoutMs)
        if (session == null) {
            P7Log.say(context, "VSCODE start FAILED")
            return Result(true, false, -1, android.os.SystemClock.elapsedRealtime() - t0)
        }
        P7Log.say(context, "VSCODE start ok port=${session.port} health=${session.startMs} ms " +
            "login=${if (session.cookie != null) "ok" else "FAIL"}")
        return Result(true, session.cookie != null, session.port,
            android.os.SystemClock.elapsedRealtime() - t0, session.cookie)
    }
}
