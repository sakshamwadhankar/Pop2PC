package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * T15-3: the import list's rules, pinned without a phone. The list persists as JSON next to
 * the App Center's installed store; a duplicate SHA-256 keeps one row; an exe runs directly,
 * an msi runs msiexec, a zip runs the single .exe found inside.
 */
class ImportedAppsTest {

    private fun tmp(tag: String): File =
        Files.createTempDirectory("ud-import-$tag-").toFile().also { it.deleteOnExit() }

    @Test
    fun kindsComeFromTheExtensionIgnoringCase() {
        assertEquals(ImportedApps.KIND_EXE, ImportedApps.kindOf("7zFM.EXE"))
        assertEquals(ImportedApps.KIND_MSI, ImportedApps.kindOf("setup.msi"))
        assertEquals(ImportedApps.KIND_ZIP, ImportedApps.kindOf("portable.zip"))
        assertEquals(ImportedApps.KIND_OTHER, ImportedApps.kindOf("readme.txt"))
        assertEquals(ImportedApps.KIND_OTHER, ImportedApps.kindOf("noext"))
    }

    @Test
    fun theDisplayNameDropsTheExtensionButKeepsTheRest() {
        assertEquals("7zFM", ImportedApps.displayName("7zFM.exe"))
        assertEquals("setup (2)", ImportedApps.displayName("setup (2).msi"))
        assertEquals("archive", ImportedApps.displayName("archive"))
        assertEquals("noext", ImportedApps.displayName("noext"))
    }

    @Test
    fun aDuplicateShaKeepsOneRow() {
        val a = ImportedApps.ImportedApp("tool", "desktop/Imported/tool.exe", "abc", 1, "exe", "pc")
        val list = listOf(a)
        assertEquals(a, ImportedApps.duplicateOf(list, "ABC"))
        assertNull(ImportedApps.duplicateOf(list, "def"))
        assertNull("an empty sha never matches", ImportedApps.duplicateOf(list, ""))
    }

    @Test
    fun launchingDependsOnTheKind() {
        fun e(kind: String, exe: String = "") =
            ImportedApps.ImportedApp("n", "desktop/Imported/n.$kind", "s", 1, kind, "pc", exe)
        assertEquals(ImportedApps.Launch.EXE, ImportedApps.launchFor(e("exe")))
        assertEquals(ImportedApps.Launch.MSI, ImportedApps.launchFor(e("msi")))
        assertEquals(ImportedApps.Launch.ZIP_EXE, ImportedApps.launchFor(e("zip", "desktop/Imported/a/b.exe")))
        assertEquals("a zip without an exe is stored, not guessed", ImportedApps.Launch.UNSUPPORTED,
            ImportedApps.launchFor(e("zip")))
        assertEquals(ImportedApps.Launch.UNSUPPORTED, ImportedApps.launchFor(e("txt")))
    }

    @Test
    fun theListSurvivesAWriteAndReadThroughTheRealJsonFile() {
        val f = File(tmp("json"), "appcenter/imported.json")
        val list = listOf(
            ImportedApps.ImportedApp("7-Zip", "desktop/Imported/7zFM.exe", "aa", 100, "exe", "pc"),
            ImportedApps.ImportedApp("setup", "desktop/Imported/setup.msi", "bb", 200, "msi", "url",
                exe = ""),
        )
        ImportedApps.writeFile(f, list)
        val back = ImportedApps.readFile(f)
        assertEquals(list, back)
        assertTrue("the file is the store", f.isFile)
    }

    @Test
    fun aCorruptListReadsAsEmptyRatherThanCrashing() {
        val f = File(tmp("bad"), "imported.json").apply { writeText("{not json") }
        assertTrue(ImportedApps.readFile(f).isEmpty())
        assertTrue(ImportedApps.readFile(File(f.parentFile, "missing.json")).isEmpty())
    }

    @Test
    fun theZipEntryPointIsTheSingleShallowExe() {
        assertEquals("7zFM.exe", ImportedApps.zipEntryPoint(listOf("7zFM.exe", "7z.dll", "readme.txt")))
        assertEquals("app/tool.exe", ImportedApps.zipEntryPoint(listOf("app/tool.exe", "app/x.dll")))
        assertEquals("dist/bin/tool.exe",
            ImportedApps.zipEntryPoint(listOf("dist/bin/tool.exe", "dist/readme.txt")))
        assertNull("two exes are ambiguous", ImportedApps.zipEntryPoint(listOf("a.exe", "b.exe")))
        assertEquals("app.exe (root wins over a folder exe)", "app.exe",
            ImportedApps.zipEntryPoint(listOf("docs/a.exe", "app.exe")))
        assertNull("no exe", ImportedApps.zipEntryPoint(listOf("a.txt")))
        assertNull("traversal names are not entry points",
            ImportedApps.zipEntryPoint(listOf("..\\a.exe", "b.txt")))
    }

    @Test
    fun zipExtractionSkipsTraversalAndFindsTheExe() {
        val zip = File(tmp("zip"), "portable.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("app/tool.exe")); z.write("MZ".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("app/data.txt")); z.write("hi".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("../evil.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        val dest = tmp("unzip")
        val names = ImportedApps.extractZip(zip, dest)
        assertTrue("app/tool.exe" in names)
        assertTrue("app/data.txt" in names)
        assertFalse("traversal entries are skipped", "../evil.txt" in names)
        assertFalse(File(dest.parentFile, "evil.txt").exists())
        assertEquals("app/tool.exe", ImportedApps.zipEntryPoint(names))
        assertTrue(File(dest, "app/tool.exe").isFile)
    }

    @Test
    fun urlFileNamesAreSafeAndKeepAnExtension() {
        assertEquals("setup.exe", ImportedApps.urlFileName("https://x.test/a/setup.exe?dl=1"))
        assertEquals("tool.zip", ImportedApps.urlFileName("https://x.test/tool.zip#top"))
        assertEquals("download.exe", ImportedApps.urlFileName("https://x.test/"))
        assertEquals("noext.exe", ImportedApps.urlFileName("https://x.test/noext"))
    }
}

