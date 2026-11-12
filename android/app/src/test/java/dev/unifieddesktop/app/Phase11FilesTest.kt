package dev.unifieddesktop.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random

/**
 * Phase 11 D-d: the phone half of CH_FILE. The layout must stay byte-identical to
 * pc/udreceiver/src/files.rs (see its `messages_roundtrip` test); these tests pin the same
 * vectors and prove the sink verifies a real transfer before it renames the .part file.
 */
class Phase11FilesTest {

    private fun tmp(tag: String): File =
        Files.createTempDirectory("ud-p11-$tag-").toFile().also { it.deleteOnExit() }

    @Test
    fun startLayoutMatchesThePc() {
        val sha = ByteArray(32) { it.toByte() }
        val m = Phase11Files.encodeStart(9, 12345, "a.txt", sha)
        assertEquals(1, m[0].toInt())
        assertEquals(9, ((m[1].toInt() and 0xFF) shl 24) or ((m[2].toInt() and 0xFF) shl 16) or
            ((m[3].toInt() and 0xFF) shl 8) or (m[4].toInt() and 0xFF))
        assertEquals(12345L, (5 until 13).fold(0L) { acc, i -> (acc shl 8) or (m[i].toLong() and 0xFF) })
        assertEquals(5, ((m[13].toInt() and 0xFF) shl 8) or (m[14].toInt() and 0xFF))
        assertEquals("a.txt", String(m, 15, 5, Charsets.UTF_8))
        assertArrayEquals(sha, m.copyOfRange(20, 52))
    }

    @Test
    fun dataAndEndCarryTheId() {
        val d = Phase11Files.encodeData(7, "hello".toByteArray(), 0, 5)
        assertEquals(2, d[0].toInt())
        assertEquals(10, d.size)
        assertEquals('h'.code.toByte(), d[5])
        val e = Phase11Files.encodeEnd(7)
        assertEquals(3, e[0].toInt())
        assertEquals(5, e.size)
    }

    @Test
    fun namesCannotEscapeTheDesktopFolder() {
        assertEquals("passwd", Phase11Files.safeName("../../etc/passwd"))
        assertEquals("x.dll", Phase11Files.safeName("C:\\Windows\\x.dll"))
        assertEquals("file", Phase11Files.safeName(".."))
    }

    @Test
    fun uniqueFileNeverOverwrites() {
        val d = tmp("unique")
        File(d, "a.txt").writeText("1")
        assertEquals("a (2).txt", Phase11Files.uniqueFile(d, "a.txt").name)
        File(d, "a (2).txt").writeText("2")
        assertEquals("a (3).txt", Phase11Files.uniqueFile(d, "a.txt").name)
    }

    @Test
    fun twoMegabyteFileArrivesVerified() {
        val srcDir = tmp("src")
        val dstDir = tmp("dst")
        val data = ByteArray(2 * 1024 * 1024)
        Random(11).nextBytes(data)
        val src = File(srcDir, "two.bin").apply { writeBytes(data) }
        val messages = ArrayList<ByteArray>()
        val sent = Phase11Files.sendFile(src) { messages.add(it); true }
        assertEquals(data.size.toLong(), sent)
        assertTrue("2 MB needs more than one DATA frame", messages.size > 2)
        messages.forEach { assertTrue("one message must fit one wire frame", it.size <= 65536) }

        val sink = Phase11Files.Sink(dstDir)
        var saved: File? = null
        for (m in messages) saved = sink.onMessage(m)?.file ?: saved
        assertEquals("two.bin", saved?.name)
        assertArrayEquals(data, saved!!.readBytes())
        assertArrayEquals(Phase11Files.sha256(data), Phase11Files.sha256(saved))
        // no .part file is left behind
        assertEquals(1, dstDir.listFiles()!!.size)
    }

    @Test
    fun corruptTransferIsRejectedAndLeavesNoFile() {
        val d = tmp("corrupt")
        val sink = Phase11Files.Sink(d)
        sink.onMessage(Phase11Files.encodeStart(1, 4, "x.bin", ByteArray(32) { 1 }))
        sink.onMessage(Phase11Files.encodeData(1, "abcd".toByteArray(), 0, 4))
        try {
            sink.onMessage(Phase11Files.encodeEnd(1))
            fail("a wrong SHA-256 must fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("verify failed"))
        }
        assertNull(d.listFiles()?.firstOrNull { it.name == "x.bin" })
        assertEquals(0, d.listFiles()!!.size)
    }

    @Test
    fun theStartKindByteIsTheLastByteAndDefaultsToFile() {
        val sha = ByteArray(32) { 3 }
        val file = Phase11Files.encodeStart(1, 2, "a.exe", sha)
        assertEquals(Phase11Files.KIND_FILE, file.last().toInt() and 0xFF)
        val imp = Phase11Files.encodeStart(1, 2, "a.exe", sha, Phase11Files.KIND_IMPORT)
        assertEquals(Phase11Files.KIND_IMPORT, imp.last().toInt() and 0xFF)
        assertEquals(file.size + 0, imp.size)
    }

    @Test
    fun anImportFlaggedTransferLandsInTheImportFolder() {
        val desk = tmp("desk")
        val imp = tmp("imp")
        val data = "MZ port".toByteArray()
        val sha = Phase11Files.sha256(data)
        val sink = Phase11Files.Sink(desk, imp)
        assertNull(sink.onMessage(Phase11Files.encodeStart(7, data.size.toLong(), "tool.exe", sha, Phase11Files.KIND_IMPORT)))
        assertNull(sink.onMessage(Phase11Files.encodeData(7, data, 0, data.size)))
        val got = sink.onMessage(Phase11Files.encodeEnd(7))
        assertEquals("tool.exe", got?.file?.name)
        assertEquals(Phase11Files.KIND_IMPORT, got?.kind)
        assertTrue("imports must not land in the desktop folder", desk.listFiles()!!.isEmpty())
        assertEquals(1, imp.listFiles()!!.size)
    }
}
