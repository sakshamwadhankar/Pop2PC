package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * B-133 residual (T14-3 item 3): the phone's route to GitHub reset mid-download five
 * times on one 102 MB tarball and every retry restarted from byte 0, so the file never
 * finished. [Phase2Data.download] now keeps a partial file and resumes with HTTP Range.
 *
 * The pure decisions (offset math, 206 handling, no-range fallback) and an integration
 * test against a LOCAL raw server that drops the connection mid-body (the reset shape
 * T14-2 measured) are both here, so no external network is needed.
 */
class Phase2DataResumeTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    // ---- pure decisions -------------------------------------------------------

    @Test
    fun rangeHeader_only_for_a_nonzero_offset() {
        assertNull(Phase2Data.rangeHeader(0))
        assertEquals("bytes=123-", Phase2Data.rangeHeader(123))
    }

    @Test
    fun redirects_followed_manually() {
        assertTrue(Phase2Data.isRedirect(301))
        assertTrue(Phase2Data.isRedirect(302))
        assertTrue(Phase2Data.isRedirect(307))
        assertTrue(Phase2Data.isRedirect(308))
        assertTrue("306 is unused/historic", !Phase2Data.isRedirect(306))
        assertTrue(!Phase2Data.isRedirect(200))
        assertTrue(!Phase2Data.isRedirect(206))
        assertTrue(!Phase2Data.isRedirect(404))
    }

    @Test
    fun contentRange_start_parsed() {
        assertEquals(123L, Phase2Data.parseContentRangeStart("bytes 123-456/789"))
        assertEquals(0L, Phase2Data.parseContentRangeStart("bytes 0-99/100"))
        assertNull(Phase2Data.parseContentRangeStart("bytes */100"))
        assertNull(Phase2Data.parseContentRangeStart(null))
        assertNull(Phase2Data.parseContentRangeStart("unsupported"))
    }

    /** T15-1 progress: the resumed fetch's total comes from the same header. */
    @Test
    fun contentRange_total_parsed() {
        assertEquals(789L, Phase2Data.parseContentRangeTotal("bytes 123-456/789"))
        assertEquals(100L, Phase2Data.parseContentRangeTotal("bytes 0-99/100"))
        assertEquals("a '*' start still names the total (416 shape)", 100L,
            Phase2Data.parseContentRangeTotal("bytes */100"))
        assertNull(Phase2Data.parseContentRangeTotal(null))
        assertNull(Phase2Data.parseContentRangeTotal("unsupported"))
    }

    /** Offset math: 206 with a matching echo resumes; everything else restarts clean. */
    @Test
    fun offset_math() {
        assertEquals(1000L, Phase2Data.resumeOffset(1000, 206, 1000))
        assertEquals("server ignored the Range -> restart clean",
            0L, Phase2Data.resumeOffset(1000, 206, 0))
        assertEquals("missing echo -> restart clean", 0L, Phase2Data.resumeOffset(1000, 206, null))
        assertEquals("200 = ranges refused -> restart clean", 0L, Phase2Data.resumeOffset(1000, 200, null))
        assertEquals("416 = range unsatisfiable -> restart clean", 0L, Phase2Data.resumeOffset(1000, 416, null))
    }

    // ---- integration against a local server that resets mid-body --------------

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /**
     * One-shot raw HTTP server. First GET (no Range): 200 + only the first [dropAfter]
     * bytes, then the socket is closed ABRUPTLY (the T14-2 reset shape). Any later GET
     * with a Range: 206 + the remainder. When [refuseRanges] is set the Range request is
     * answered 200 with the FULL body (the no-range fallback shape).
     */
    private class ResetServer(private val body: ByteArray, private val dropAfter: Int, private val refuseRanges: Boolean) {
        private val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        val rangeRequests = java.util.Collections.synchronizedList(ArrayList<String>())

        fun start() {
            Thread({
                while (true) {
                    val s = try { server.accept() } catch (_: Throwable) { return@Thread }
                    handle(s)
                }
            }, "test-server").start()
        }

        fun close() { server.close() }

        private fun handle(s: Socket) {
            s.use { sock ->
                val reader = sock.getInputStream().buffered().let { java.io.BufferedReader(it.reader(Charsets.ISO_8859_1)) }
                val request = reader.readLine() ?: return
                var range: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }
                if (range != null) rangeRequests.add(range)
                val out = sock.getOutputStream()
                if (range == null || refuseRanges) {
                    // 200: the first (Range-less) attempt drops mid-body like the real
                    // resets; a later Range request (ranges refused) carries the full body.
                    val cut = if (range == null) dropAfter else body.size
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.ISO_8859_1)
                    )
                    out.write(body, 0, cut)
                    out.flush()
                    if (cut < body.size) sock.shutdownOutput()
                    // Abrupt close (RST on some stacks) is the point of this server.
                } else {
                    // 206 with the remainder.
                    val offset = range.removePrefix("bytes=").removeSuffix("-").toLong()
                    out.write(
                        ("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $offset-${body.size - 1}/${body.size}\r\n" +
                            "Content-Length: ${body.size - offset}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.ISO_8859_1)
                    )
                    out.write(body, offset.toInt(), body.size - offset.toInt())
                    out.flush()
                    sock.shutdownOutput()
                }
            }
        }
    }

    @Test
    fun interrupted_download_resumes_and_verifies_sha() {
        val body = ByteArray(1 shl 20) { (it % 251).toByte() }
        val sha = sha256Of(body)
        val srv = ResetServer(body, dropAfter = 300_000, refuseRanges = false)
        srv.start()
        try {
            val dir = tmp.newFolder()
            val f = Phase2Data.download(dir, "http://127.0.0.1:${srv.port}/wine.tar", sha, "wine.tar", log = { })
            assertEquals("the file completed after the resume", body.size.toLong(), f.length())
            assertEquals("final sha verified as today", sha, sha256(f))
            assertTrue("at least ONE resume request was sent",
                srv.rangeRequests.any { it.startsWith("bytes=") })
        } finally {
            srv.close()
        }
    }

    @Test
    fun server_that_refuses_ranges_restarts_clean_and_verifies_sha() {
        val body = ByteArray(200_000) { ((it * 7) % 253).toByte() }
        val sha = sha256Of(body)
        val srv = ResetServer(body, dropAfter = 100_000, refuseRanges = true)
        srv.start()
        try {
            val dir = tmp.newFolder()
            val f = Phase2Data.download(dir, "http://127.0.0.1:${srv.port}/wine.tar", sha, "wine.tar", log = { })
            assertEquals(body.size.toLong(), f.length())
            assertEquals(sha, sha256(f))
            // The server answered every request 200; no resume was possible.
            assertTrue(srv.rangeRequests.isNotEmpty())
        } finally {
            srv.close()
        }
    }

    @Test
    fun cached_complete_file_is_still_a_cache_hit() {
        val body = ByteArray(10_000) { ((it * 3) % 249).toByte() }
        val sha = sha256Of(body)
        val dir = tmp.newFolder()
        val f = File(dir, "wine.tar")
        FileOutputStream(f).use { it.write(body) }
        val got = Phase2Data.download(dir, "http://127.0.0.1:1/nothing", sha, "wine.tar", log = { })
        assertEquals(body.size.toLong(), got.length())
    }

    private fun sha256Of(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
