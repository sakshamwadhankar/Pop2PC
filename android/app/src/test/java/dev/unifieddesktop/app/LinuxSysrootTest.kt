package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * T15-6 item 2: the sysroot manifest + fetch wiring, all local-JVM.
 * The fetch tests run against a LOCAL raw server (the T14-3 pattern: the first GET
 * drops mid-body, later Range requests resume) - no external network, and the real
 * pinned URLs are never touched here.
 */
class LinuxSysrootTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------- manifest parse

    @Test
    fun pinnedManifestParsesIntoThreeComponents() {
        val m = LinuxSysroot.parseManifest(LinuxSysroot.MANIFEST_JSON)
        assertEquals(3, m.components.size)
        val busybox = m.components[0]
        assertEquals("busybox", busybox.name)
        assertEquals("deb", busybox.format)
        assertEquals("busybox", busybox.unpackDir)
        assertEquals(822236L, busybox.size)
        assertEquals(64, busybox.sha256.length)
        assertTrue(busybox.unpackedPaths.contains("bin/busybox"))
        val glibc = m.components[1]
        assertEquals("glibc", glibc.name)
        assertTrue(glibc.unpackedPaths.contains("lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"))
        assertTrue(glibc.unpackedPaths.contains("lib/aarch64-linux-gnu/libc.so.6"))
        val ff = m.components[2]
        assertEquals("firefox", ff.name)
        assertEquals("tar.xz", ff.format)
        assertEquals("firefox", ff.unpackDir)
        assertEquals(81548980L, ff.size)
        assertTrue(ff.unpackedPaths.contains("firefox/firefox"))
    }

    @Test
    fun everyPinnedUrlHasAtLeastOneVerifiedMirror() {
        val m = LinuxSysroot.parseManifest(LinuxSysroot.MANIFEST_JSON)
        for (c in m.components) {
            assertTrue("${c.name} needs a mirror", c.mirrorUrls.isNotEmpty())
            assertEquals("ladder = primary + mirrors, distinct",
                listOf(c.url) + c.mirrorUrls, LinuxSysroot.urlsFor(c))
        }
    }

    @Test
    fun malformedManifestJsonIsRejected() {
        val badSha = try {
            LinuxSysroot.parseManifest(
                """{"components":[{"name":"x","url":"u","sha256":"nothex","size":1,"format":"raw","unpackDir":"d","unpackedPaths":["p"]}]}""",
            )
            false
        } catch (_: IllegalArgumentException) { true }
        assertTrue("bad sha256 must be rejected", badSha)
        val noPaths = try {
            LinuxSysroot.parseManifest(
                """{"components":[{"name":"x","url":"u","sha256":"${"0".repeat(64)}","size":1,"format":"raw","unpackDir":"d","unpackedPaths":[]}]}""",
            )
            false
        } catch (_: IllegalArgumentException) { true }
        assertTrue("empty unpackedPaths must be rejected", noPaths)
        val traversal = try {
            LinuxSysroot.parseManifest(
                """{"components":[{"name":"x","url":"u","sha256":"${"0".repeat(64)}","size":1,"format":"raw","unpackDir":"d","unpackedPaths":["../escape"]}]}""",
            )
            false
        } catch (_: IllegalArgumentException) { true }
        assertTrue("a traversal sentinel must be rejected", traversal)
    }

    // ------------------------------------------------------------ pure decisions

    @Test
    fun componentSkipNeedsEverySignal() {
        val sha = "a".repeat(64)
        assertFalse(LinuxSysroot.componentSkip(sha, sha, sentinelsOk = true, present = false))
        assertFalse(LinuxSysroot.componentSkip(null, sha, sentinelsOk = true, present = true))
        assertFalse("wrong pinned digest must refetch",
            LinuxSysroot.componentSkip("b".repeat(64), sha, sentinelsOk = true, present = true))
        assertFalse(LinuxSysroot.componentSkip(sha, sha, sentinelsOk = false, present = true))
        assertTrue(LinuxSysroot.componentSkip(sha, sha, sentinelsOk = true, present = true))
    }

    @Test
    fun pathsOkNeedsAllSentinels() {
        assertFalse(LinuxSysroot.pathsOk(emptyList()))
        assertFalse(LinuxSysroot.pathsOk(listOf(true, false)))
        assertTrue(LinuxSysroot.pathsOk(listOf(true, true)))
    }

    @Test
    fun componentManifestRoundTrip() {
        val sha = "c".repeat(64)
        assertEquals(sha, LinuxSysroot.parseComponentManifest(LinuxSysroot.componentManifestText(sha)))
        assertEquals(null, LinuxSysroot.parseComponentManifest("garbage"))
        assertEquals(null, LinuxSysroot.parseComponentManifest("sha256=zzz"))
        assertEquals(null, LinuxSysroot.parseComponentManifest(null))
    }

    // --------------------------------------------------- integration: local server

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ---- hand-built tar (same shape as TarReaderTest's fixtures) ----

    private fun octal(v: Long, width: Int): ByteArray =
        (v.toString(8).padStart(width - 1, '0') + "\u0000").toByteArray()

    private fun tarHeader(name: String, size: Long, type: Char): ByteArray {
        val b = ByteArray(512)
        fun put(s: ByteArray, off: Int, len: Int) = System.arraycopy(s, 0, b, off, minOf(len, s.size))
        put(name.toByteArray(), 0, 100)
        put(octal(420L, 8), 100, 8)
        put(octal(0L, 8), 108, 8)
        put(octal(0L, 8), 116, 8)
        put(octal(size, 12), 124, 12)
        put(octal(1_767_000_000L, 12), 136, 12)
        b[156] = type.code.toByte()
        put("ustar\u000000".toByteArray(), 257, 8)
        return b
    }

    private fun padded(bytes: ByteArray): ByteArray =
        bytes + ByteArray((512 - (bytes.size % 512)) % 512)

    /** A minimal tar.gz with two files: bin/prog (the sentinel) + data.txt - member
     *  names relative to the component's unpackDir, like the Firefox tarball is. */
    private fun gzTar(name1: String, name2: String, payload1: ByteArray): ByteArray {
        val p2 = "hello linux sysroot\n".toByteArray()
        val raw = ByteArrayOutputStream().also { out ->
            out.write(tarHeader(name1, payload1.size.toLong(), '0') + padded(payload1))
            out.write(tarHeader(name2, p2.size.toLong(), '0') + padded(p2))
            out.write(ByteArray(1024))
        }
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw.toByteArray()) }
        return gz.toByteArray()
    }

    /**
     * One-shot raw HTTP server: the FIRST request gets 200 + only [dropAfter] bytes,
     * then the socket closes abruptly (the T14-2 reset shape); any Range request gets
     * 206 + the remainder.
     */
    private class ResetServer(private val body: ByteArray, private val dropAfter: Int) {
        private val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        val requests = java.util.Collections.synchronizedList(ArrayList<String>())
        val rangeRequests = java.util.Collections.synchronizedList(ArrayList<String>())
        fun start() {
            Thread({
                while (true) {
                    val s = try { server.accept() } catch (_: Throwable) { return@Thread }
                    handle(s)
                }
            }, "sysroot-test-server").start()
        }
        fun close() { server.close() }

        private fun handle(s: Socket) {
            s.use { sock ->
                val reader = sock.getInputStream().buffered().let {
                    java.io.BufferedReader(it.reader(Charsets.ISO_8859_1))
                }
                val request = reader.readLine() ?: return
                var range: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }
                requests.add(request)
                if (range != null) rangeRequests.add(range)
                val out = sock.getOutputStream()
                if (range == null) {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.ISO_8859_1))
                    out.write(body, 0, dropAfter)
                    out.flush()
                    if (dropAfter < body.size) sock.shutdownOutput() // abrupt close
                } else {
                    val offset = range.removePrefix("bytes=").removeSuffix("-").toLong()
                    out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $offset-${body.size - 1}/${body.size}\r\n" +
                        "Content-Length: ${body.size - offset}\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.ISO_8859_1))
                    out.write(body, offset.toInt(), body.size - offset.toInt())
                    out.flush()
                    sock.shutdownOutput()
                }
            }
        }
    }

    private fun component(url: String, sha: String, size: Long) =
        LinuxSysroot.Component(
            name = "testapp", kind = "app", url = url, mirrorUrls = listOf(),
            sha256 = sha, size = size, format = "tar.gz", unpackDir = "testapp",
            unpackedPaths = listOf("bin/prog"),
        )

    @Test
    fun midBodyDropResumesUnpacksAndWritesTheComponentManifest() {
        val payload = ByteArray(1 shl 20) { (it % 251).toByte() }
        val tar = gzTar("bin/prog", "data.txt", payload)
        // Cut mid-body: half the (compressed) tar, wherever gzip put it.
        val srv = ResetServer(tar, dropAfter = tar.size / 2)
        srv.start()
        try {
            val downloads = tmp.newFolder()
            val sysroot = tmp.newFolder()
            val comp = component("http://127.0.0.1:${srv.port}/app.tar.gz", sha256(tar), tar.size.toLong())
            val log = ArrayList<String>()
            val ok = LinuxSysroot.ensureTree(LinuxSysroot.Manifest(listOf(comp)), downloads, sysroot,
                urlFor = { listOf(comp.url) }, log = { log.add(it) })
            assertTrue("ensureTree must succeed after the resume (log: ${log.joinToString(" | ")})", ok)
            val prog = File(sysroot, "testapp/bin/prog")
            assertTrue("sentinel unpacked", prog.isFile)
            assertEquals(payload.size.toLong(), prog.length())
            assertEquals("data.txt unpacked too",
                "hello linux sysroot\n", File(sysroot, "testapp/data.txt").readText())
            assertEquals("component manifest pins the digest", comp.sha256,
                LinuxSysroot.parseComponentManifest(
                    File(sysroot, "testapp/${LinuxSysroot.COMPONENT_MANIFEST}").readText()))
            assertTrue("the first body was cut (the reset shape happened)", srv.requests.size >= 2)
            assertTrue("at least one Range resume request was sent", srv.rangeRequests.isNotEmpty())
        } finally {
            srv.close()
        }
    }

    @Test
    fun verifiedTreeIsSkippedWithoutAnyNetwork() {
        val payload = "prog bytes".toByteArray()
        val tar = gzTar("bin/prog", "data.txt", payload)
        val downloads = tmp.newFolder()
        val sysroot = tmp.newFolder()
        val comp = component("http://127.0.0.1:1/app.tar.gz", sha256(tar), tar.size.toLong())
        // Stage the tree + component manifest by hand (the post-first-fetch state).
        val dir = File(sysroot, "testapp"); dir.mkdirs()
        File(dir, "bin/prog").let { it.parentFile!!.mkdirs(); it.writeBytes(payload) }
        File(dir, LinuxSysroot.COMPONENT_MANIFEST).writeText(LinuxSysroot.componentManifestText(comp.sha256))
        val log = ArrayList<String>()
        // Port 1 is dead on purpose: any fetch attempt fails the test through the log.
        assertTrue(LinuxSysroot.ensureTree(LinuxSysroot.Manifest(listOf(comp)), downloads, sysroot,
            urlFor = { listOf(comp.url) }, log = { log.add(it) }))
        assertTrue("skip must be logged", log.any { it.contains("skip tree verified") })
        assertEquals("no partial downloaded", 0, downloads.listFiles()?.size ?: 0)
        assertTrue("the staged sentinel survived untouched",
            File(sysroot, "testapp/bin/prog").readBytes().contentEquals(payload))
    }

    @Test
    fun shaMismatchFailsTheComponentWithoutTrustingTheTree() {
        val tar = gzTar("bin/prog", "data.txt", "wrong bytes".toByteArray())
        val srv = ResetServer(tar, dropAfter = tar.size) // full body, no drop
        srv.start()
        try {
            val downloads = tmp.newFolder()
            val sysroot = tmp.newFolder()
            val comp = component("http://127.0.0.1:${srv.port}/app.tar.gz", "f".repeat(64), tar.size.toLong())
            val log = ArrayList<String>()
            assertFalse("mismatched digest must fail the component",
                LinuxSysroot.ensureTree(LinuxSysroot.Manifest(listOf(comp)), downloads, sysroot,
                    urlFor = { listOf(comp.url) }, log = { log.add(it) }))
            assertTrue("failure must be logged",
                log.any { it.contains("FAILED") && it.contains("sha mismatch") })
            assertFalse("a failed component never leaves a trusted tree",
                File(sysroot, "testapp/${LinuxSysroot.COMPONENT_MANIFEST}").exists())
        } finally {
            srv.close()
        }
    }
}
