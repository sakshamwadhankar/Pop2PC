package dev.unifieddesktop.app

import java.util.Locale

/**
 * T15-1: the one line a user sees while a big first-run download runs. T14-1 recorded that
 * an empty-looking progress area reads as "frozen", so every multi-MB fetch (code-server,
 * the Wine runtime, an App Center installer) reports MB and percent as it advances.
 *
 * Pure and unit-tested; callers throttle to one post per whole percent so a 16 KiB chunk
 * never floods the UI thread.
 */
object ProgressText {

    /** Decimal MB (1 MB = 1,000,000 B), the unit every earlier report and the download
     *  logs use: the wine tarball's 87,423,250 B partial reads as "87.4 MB", not 83.4. */
    private const val MB = 1_000_000.0

    /** "Downloading VS Code: 12.3 of 117.0 MB (10%)"; without a total just the MB count. */
    fun line(label: String, bytes: Long, total: Long): String {
        val mb = bytes / MB
        if (total <= 0) {
            return String.format(Locale.ROOT, "%s: %.1f MB", label, mb)
        }
        val pct = (100.0 * bytes / total).toInt().coerceIn(0, 100)
        val fullMb = total / MB
        return String.format(Locale.ROOT, "%s: %.1f of %.1f MB (%d%%)", label, mb, fullMb, pct)
    }
}
