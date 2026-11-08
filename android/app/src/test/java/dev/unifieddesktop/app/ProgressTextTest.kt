package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** T15-1: every big first-run download must read as alive (MB + percent), never frozen. */
class ProgressTextTest {

    @Test
    fun reports_mb_and_percent_when_the_total_is_known() {
        assertEquals(
            "Downloading VS Code: 58.5 of 117.0 MB (50%)",
            ProgressText.line("Downloading VS Code", 58_500_000, 117_000_000),
        )
    }

    @Test
    fun reports_only_mb_when_the_server_gave_no_total() {
        // The exact T14-3 wine partial reads as the 87.4 MB the reports recorded.
        assertEquals(
            "Preparing Windows runtime: 87.4 MB",
            ProgressText.line("Preparing Windows runtime", 87_423_250, -1),
        )
    }

    @Test
    fun percent_is_clamped_to_100() {
        assertEquals(
            "Downloading Notepad++: 9.0 of 9.0 MB (100%)",
            ProgressText.line("Downloading Notepad++", 9_000_000, 9_000_000),
        )
        assertEquals(
            "Downloading Notepad++: 10.0 of 9.0 MB (100%)",
            ProgressText.line("Downloading Notepad++", 10_000_000, 9_000_000),
        )
    }

    @Test
    fun uses_a_dot_decimal_separator_regardless_of_locale() {
        // Locale.ROOT: a device set to a comma locale must not produce "12,3 of ...".
        assertEquals(
            "Downloading VS Code: 1.5 of 117.0 MB (1%)",
            ProgressText.line("Downloading VS Code", 1_500_000, 117_000_000),
        )
    }
}
