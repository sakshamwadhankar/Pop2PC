package dev.unifieddesktop.app

/**
 * B-139: one pure description of the start menu's on-desktop geometry. The view lays itself
 * out from these numbers and the tests click every tile/row at its computed centre, so a
 * layout change cannot silently move a hit target away from the picture.
 *
 * Measured layout (StartMenuView + ShellRoot.openStartMenu): the panel is 560 wide, 8 px from
 * the left edge and 8 px above the taskbar, with 12 px padding; inside are the 40 px search
 * box, the 88 px pinned row (four equal tiles, 10 px below the search), the 24 px "All apps"
 * label (8 px gap) and 40 px rows (2 px gap above the list).
 */
object StartMenuLayout {
    const val PANEL_W = 560
    const val EDGE = 8
    const val PAD = 12
    const val SEARCH_H = 40
    const val PINNED_GAP = 10
    const val PINNED_H = 88
    const val LABEL_GAP = 8
    const val LABEL_H = 24
    const val LIST_GAP = 2
    const val ROW_H = 40
    const val TILES = 4

    /** The view's preferred height: fixed chrome plus one 40 px row per entry. */
    const val BASE_H = 204

    fun panelWidth(): Int = PANEL_W

    fun panelHeight(rows: Int): Int = BASE_H + rows.coerceAtLeast(0) * ROW_H

    /** Height of the "Imported" section label row (T15-3). */
    const val IMPORTED_LABEL_H = 34

    /**
     * Extra panel height for the T15-3 Imported section: its label plus one row per import.
     * Zero when the user has no imports, so the menu looks exactly as before.
     */
    fun importedExtra(imported: Int): Int =
        if (imported <= 0) 0 else IMPORTED_LABEL_H + imported * ROW_H

    /** Height of the "Windows programs" section label (shortcuts an installer created). */
    const val WINDOWS_LABEL_H = 34

    /** Extra panel height for the shortcut section (T15-3 MSI installs). */
    fun windowsExtra(shortcuts: Int): Int =
        if (shortcuts <= 0) 0 else WINDOWS_LABEL_H + shortcuts * ROW_H

    /** Top edge of the panel for a desktop of [desktopH] with a [taskbarH] taskbar. */
    fun panelTop(desktopH: Int, taskbarH: Int, rows: Int): Int =
        desktopH - taskbarH - EDGE - panelHeight(rows)

    fun tileWidth(): Int = (PANEL_W - 2 * PAD) / TILES

    /** The pinned-row tile [index] (0..3) as left/top/right/bottom. */
    fun tileRect(desktopH: Int, taskbarH: Int, rows: Int, index: Int): IntArray {
        val top = panelTop(desktopH, taskbarH, rows) + PAD + SEARCH_H + PINNED_GAP
        val left = EDGE + PAD + index.coerceIn(0, TILES - 1) * tileWidth()
        return intArrayOf(left, top, left + tileWidth(), top + PINNED_H)
    }

    /** One "All apps" row as left/top/right/bottom, counting from the first row. */
    fun rowRect(desktopH: Int, taskbarH: Int, rows: Int, index: Int): IntArray {
        val top = panelTop(desktopH, taskbarH, rows) + PAD + SEARCH_H + PINNED_GAP + PINNED_H +
            LABEL_GAP + LABEL_H + LIST_GAP + index.coerceAtLeast(0) * ROW_H
        return intArrayOf(EDGE + PAD, top, EDGE + PANEL_W - PAD, top + ROW_H)
    }

    /** Which pinned tile is at (x, y), or -1. */
    fun tileAt(x: Int, y: Int, desktopH: Int, taskbarH: Int, rows: Int): Int {
        for (i in 0 until TILES) {
            val r = tileRect(desktopH, taskbarH, rows, i)
            if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return i
        }
        return -1
    }

    /** Which "All apps" row is at (x, y), or -1. Only real rows count. */
    fun rowAt(x: Int, y: Int, desktopH: Int, taskbarH: Int, rows: Int): Int {
        for (i in 0 until rows.coerceAtLeast(0)) {
            val r = rowRect(desktopH, taskbarH, rows, i)
            if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return i
        }
        return -1
    }
}
