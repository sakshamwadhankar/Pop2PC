package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T-006: folder/file names typed on the PC must be honest and stay inside the folder. */
class FileNamesTest {

    @Test
    fun cleanAcceptsPlainNamesAndKeepsExtensions() {
        assertEquals("tester-file-1.bin", FileNames.clean("tester-file-1.bin"))
        assertEquals("notes.txt", FileNames.clean("  notes.txt  "))
        assertEquals("New folder 2", FileNames.clean("New folder 2"))
    }

    @Test
    fun cleanRejectsEmptyDotsAndPathSeparators() {
        assertNull(FileNames.clean(""))
        assertNull(FileNames.clean("   "))
        assertNull(FileNames.clean("."))
        assertNull(FileNames.clean(".."))
        assertNull(FileNames.clean("../outside"))
        assertNull(FileNames.clean("a/b"))
        assertNull(FileNames.clean("a\\b"))
        assertNull(FileNames.clean("nul\u0000name"))
    }

    @Test
    fun uniqueFolderNeverCollides() {
        assertEquals("New folder", FileNames.uniqueFolder(emptySet()))
        assertEquals("New folder 2", FileNames.uniqueFolder(setOf("New folder")))
        assertEquals("New folder 4", FileNames.uniqueFolder(setOf("New folder", "New folder 2", "New folder 3")))
    }
}
