package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-139: the start menu's hit geometry must follow the picture at every desktop size the PC
 * asks for - the full 1728x1080 desktop and the governor's reduced operating point. The phone
 * run (2026-11-09) showed 5/5 PC clicks landing on the intended tile/row; these tests pin the
 * arithmetic behind that so a future layout edit cannot move a target silently.
 */
class StartMenuLayoutTest {

    private val taskbar = Theme.TASKBAR_H

    private fun checkDesktop(desktopH: Int) {
        val rows = 7 // pinned 4 + VS Code + Google Tasks + Notepad++
        val top = StartMenuLayout.panelTop(desktopH, taskbar, rows)
        assertTrue("panel must not start above the desktop: top=$top", top >= 0)
        val bottom = top + StartMenuLayout.panelHeight(rows)
        assertEquals("panel must sit 8 px above the taskbar",
            desktopH - taskbar - StartMenuLayout.EDGE, bottom)

        for (i in 0 until StartMenuLayout.TILES) {
            val r = StartMenuLayout.tileRect(desktopH, taskbar, rows, i)
            val cx = (r[0] + r[2]) / 2
            val cy = (r[1] + r[3]) / 2
            assertEquals("tile centre must hit tile $i", i,
                StartMenuLayout.tileAt(cx, cy, desktopH, taskbar, rows))
        }

        for (i in 0 until rows) {
            val r = StartMenuLayout.rowRect(desktopH, taskbar, rows, i)
            val cx = (r[0] + r[2]) / 2
            val cy = (r[1] + r[3]) / 2
            assertEquals("row centre must hit row $i", i,
                StartMenuLayout.rowAt(cx, cy, desktopH, taskbar, rows))
        }

        // A click outside the panel (desktop background) is nobody's.
        assertEquals(-1, StartMenuLayout.tileAt(desktopH - 2, desktopH - 2, desktopH, taskbar, rows))
        assertEquals(-1, StartMenuLayout.rowAt(StartMenuLayout.PANEL_W + 40,
            StartMenuLayout.panelTop(desktopH, taskbar, rows) + 10, desktopH, taskbar, rows))
    }

    @Test
    fun tiles_and_rows_hit_their_centres_on_the_full_desktop() {
        checkDesktop(1080)
    }

    @Test
    fun tiles_and_rows_hit_their_centres_at_the_governors_reduced_point() {
        // The governor keeps the shell at the negotiated desktop and shrinks only the encoder
        // surface (1296x810), so the desktop size stays 1728x1080; this also covers a smaller
        // negotiated desktop, which must keep the same relative geometry.
        checkDesktop(810)
    }

    @Test
    fun tiles_are_inside_the_panel_and_do_not_overlap() {
        val rows = 7
        for (i in 0 until StartMenuLayout.TILES) {
            val a = StartMenuLayout.tileRect(1080, taskbar, rows, i)
            assertTrue(a[0] >= StartMenuLayout.EDGE)
            assertTrue(a[2] <= StartMenuLayout.EDGE + StartMenuLayout.PANEL_W)
            if (i > 0) {
                val prev = StartMenuLayout.tileRect(1080, taskbar, rows, i - 1)
                assertEquals("tiles must tile the row exactly", prev[2], a[0])
            }
        }
    }

    @Test
    fun the_view_and_the_tests_share_the_panel_height() {
        assertEquals(204 + 7 * 40, StartMenuLayout.panelHeight(7))
        assertEquals(204, StartMenuLayout.panelHeight(0))
    }
}
