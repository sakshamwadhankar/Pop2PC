package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * B-137 (T15-2): the Box64 bundle must not be re-downloaded when the unpacked tree is
 * already good, and a 504/flaky primary must fall through to a mirror that serves the
 * SAME pinned bytes.
 *
 * The pure decisions (skip + ladder), the ladder against a dead primary and a live
 * mirror, and the real fetch+unpack path against a local server are all here, so the
 * suite needs no external network.
 */
class Phase2DataBundleTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    // ---- pure skip decision ---------------------------------------------------

    @Test
    fun skip_only_when_manifest_sha_matches_sentinels_and_tree_present() {
        val sha = "a".repeat(64)
        assertTrue(Phase2Data.bundleSkip(sha, sha, sentinelsOk = true, present = true))
        assertFalse("manifest names another bundle",
            Phase2Data.bundleSkip("b".repeat(64), sha, sentinelsOk = true, present = true))
        assertFalse("a sentinel no longer hashes",
            Phase2Data.bundleSkip(sha, sha, sentinelsOk = false, present = true))
        assertFalse("tree missing",
            Phase2Data.bundleSkip(sha, sha, sentinelsOk = true, present = false))
        assertFalse("no manifest yet (pre-B-137 tree is adopted separately)",
            Phase2Data.bundleSkip(null, sha, sentinelsOk = true, present = true))
    }

    @Test
    fun manifest_roundtrip_and_garbage() {
        val sha = "1d4a8787a8a92267c1be673ec194c69e3bb33d14c800575d94131084142fcff0"
        assertEquals(sha, Phase2Data.parseBundleManifest(Phase2Data.bundleManifestText(sha)))
        assertEquals(sha, Phase2Data.parseBundleManifest("junk\nsha256=$sha\nmore\n"))
        assertNull(Phase2Data.parseBundleManifest(null))
        assertNull(Phase2Data.parseBundleManifest("sha256=not-a-digest"))
        assertNull(Phase2Data.parseBundleManifest("sha256=${"g".repeat(64)}"))
    }

    @Test
    fun mirror_ladder_keeps_primary_first_and_dedupes() {
        assertEquals(
            listOf("https://primary/x", "https://mirror/x"),
            Phase2Data.bundleLadder("https://primary/x", listOf("https://mirror/x")),
        )
        assertEquals(
            listOf("https://primary/x", "https://mirror/x"),
            Phase2Data.bundleLadder(
                "https://primary/x",
                listOf(" https://mirror/x ", "", "https://primary/x"),
            ),
        )
        assertEquals(listOf("https://primary/x"), Phase2Data.bundleLadder("https://primary/x", emptyList()))
    }

    // ---- ladder against a dead primary and a live mirror -----------------------

    @Test
    fun primary_failure_falls_through_to_the_mirror() {
        val body = ByteArray(300_000) { ((it * 11) % 251).toByte() }
        val sha = sha256Of(body)
        val live = OneShotServer(body)
        live.start()
        val deadPort = freePort()
        try {
            val dir = tmp.newFolder()
            val log = StringBuilder()
            val f = Phase2Data.downloadWithMirrors(
                dir,
                listOf("http://127.0.0.1:$deadPort/bundle.tar.gz", "http://127.0.0.1:${live.port}/bundle.tar.gz"),
                sha,
                "bundle.tar.gz",
                log = { log.append(it).append('\n') },
            )
            assertEquals(body.size.toLong(), f.length())
            assertEquals(sha, sha256(f))
            assertTrue("the primary failure is logged", log.contains("url 1/2 failed"))
            assertTrue("the mirror attempt is logged", log.contains("mirror 2/2"))
        } finally {
            live.close()
        }
    }

    @Test(expected = java.io.IOException::class)
    fun every_url_failing_throws_the_last_error() {
        val dead = freePort()
        val dir = tmp.newFolder()
        Phase2Data.downloadWithMirrors(
            dir,
            listOf("http://127.0.0.1:$dead/a", "http://127.0.0.1:$dead/b"),
            "a".repeat(64),
            "bundle.tar.gz",
            log = { },
        )
    }

    // ---- fetch + unpack, then the skip decision on the real tree ---------------

    @Test
    fun fetch_unpacks_the_tree_and_writes_the_manifest() {
        val payload = "bundle-member".toByteArray()
        val tarGz = gzip(tarWithFile("./${Phase2Data.BUNDLE_DIR}/hello.txt", payload))
        val sha = sha256Of(tarGz)
        val srv = OneShotServer(tarGz)
        srv.start()
        try {
            val rootfs = tmp.newFolder("rootfs")
            val downloads = tmp.newFolder("downloads")
            Phase2Data.fetchBundleTo(
                rootfs,
                downloads,
                listOf("http://127.0.0.1:${srv.port}/bundle.tar.gz"),
                sha,
                log = { },
            )
            val got = File(rootfs, "${Phase2Data.BUNDLE_DIR}/hello.txt")
            assertTrue("member unpacked", got.isFile)
            assertEquals(String(payload), got.readText())
            val manifest = File(rootfs, "${Phase2Data.BUNDLE_DIR}/${Phase2Data.BUNDLE_MANIFEST}")
            assertTrue("manifest written", manifest.isFile)
            assertEquals(sha, Phase2Data.parseBundleManifest(manifest.readText()))

            // With the sentinels treated as verified (the synthetic tar has none), the
            // manifest now satisfies the skip decision - no second download needed.
            assertTrue(
                Phase2Data.bundleTreeVerified(
                    File(rootfs, Phase2Data.BUNDLE_DIR),
                    sha,
                    sentinelCheck = { true },
                    log = { },
                ),
            )
            // A different pinned digest must NOT be skipped.
            assertFalse(
                Phase2Data.bundleTreeVerified(
                    File(rootfs, Phase2Data.BUNDLE_DIR),
                    "f".repeat(64),
                    sentinelCheck = { true },
                    log = { },
                ),
            )
        } finally {
            srv.close()
        }
    }

    @Test
    fun a_manifest_mismatch_forces_a_refetch_even_when_sentinels_match() {
        val dir = tmp.newFolder("tree")
        File(dir, Phase2Data.BUNDLE_MANIFEST).writeText(Phase2Data.bundleManifestText("a".repeat(64)))
        assertFalse(
            Phase2Data.bundleTreeVerified(
                dir,
                "b".repeat(64),
                sentinelCheck = { true },
                log = { },
            ),
        )
    }

    // ---- adoption of a pre-manifest tree (the phone's existing install) -------

    @Test
    fun a_full_pre_manifest_tree_is_adopted_once_then_skipped() {
        val sha = "1d4a8787a8a92267c1be673ec194c69e3bb33d14c800575d94131084142fcff0"
        val dir = tmp.newFolder("old-tree")
        // Real tree: 290 files/symlinks + 17 dirs, i.e. 307 entries recursively.
        repeat(300) { File(dir, "lib$it.so").writeText("x") }
        repeat(7) { File(dir, "sub$it").mkdirs() }
        val log = StringBuilder()

        // No manifest yet, sentinels good, full tree -> adopted and marked.
        assertTrue(
            Phase2Data.bundleTreeVerified(
                dir, sha, sentinelCheck = { true }, log = { log.append(it).append('\n') },
            ),
        )
        assertEquals(sha, Phase2Data.parseBundleManifest(File(dir, Phase2Data.BUNDLE_MANIFEST).readText()))
        assertTrue("adoption is logged", log.contains("BUNDLE adopted existing tree entries=308"))
        // Second bootstrap: plain skip, no adoption log.
        assertTrue(Phase2Data.bundleTreeVerified(dir, sha, sentinelCheck = { true }, log = { }))
    }

    @Test
    fun a_short_pre_manifest_tree_is_not_adopted() {
        val dir = tmp.newFolder("short-tree")
        repeat(20) { File(dir, "lib$it.so").writeText("x") }
        assertFalse(
            Phase2Data.bundleTreeVerified(
                dir, "a".repeat(64), sentinelCheck = { true }, log = { },
            ),
        )
        assertFalse("no manifest is written for a rejected tree", File(dir, Phase2Data.BUNDLE_MANIFEST).exists())
    }

    @Test
    fun second_bootstrap_skips_the_network_entirely() {
        val payload = "bundle-member".toByteArray()
        val tarGz = gzip(tarWithFile("./${Phase2Data.BUNDLE_DIR}/hello.txt", payload))
        val sha = sha256Of(tarGz)
        val srv = OneShotServer(tarGz)
        srv.start()
        try {
            val rootfs = tmp.newFolder("rootfs-skip")
            val downloads = tmp.newFolder("downloads-skip")
            val log = StringBuilder()
            val urls = listOf("http://127.0.0.1:${srv.port}/bundle.tar.gz")

            // Cold: one fetch, one unpack, manifest written.
            Phase2Data.fetchBundleTo(
                rootfs, downloads, urls, sha,
                log = { log.append(it).append('\n') },
                sentinelCheck = { true },
            )
            assertEquals(1, srv.requests.get())

            // Warm: the verified tree must mean ZERO bundle bytes over the wire.
            Phase2Data.fetchBundleTo(
                rootfs, downloads, urls, sha,
                log = { log.append(it).append('\n') },
                sentinelCheck = { true },
            )
            assertEquals("second bootstrap must not touch the network", 1, srv.requests.get())
            assertTrue("skip is logged", log.contains("BUNDLE skip tree verified"))
        } finally {
            srv.close()
        }
    }

    // ---- helpers --------------------------------------------------------------

    private fun sha256(f: File): String = sha256Of(f.readBytes())

    private fun sha256Of(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    /** Minimal one-member ustar archive; [Phase2Data.readTar] does not check checksums. */
    private fun tarWithFile(name: String, payload: ByteArray): ByteArray {
        fun octal(v: Long, width: Int): ByteArray =
            (v.toString(8).padStart(width - 1, '0') + "\u0000").toByteArray()

        val h = ByteArray(512)
        fun put(off: Int, bytes: ByteArray) = System.arraycopy(bytes, 0, h, off, minOf(bytes.size, 512 - off))
        put(0, name.toByteArray())
        put(100, octal(420L, 8))
        put(108, octal(0L, 8))
        put(116, octal(0L, 8))
        put(124, octal(payload.size.toLong(), 12))
        put(136, octal(1_767_000_000L, 12))
        h[156] = '0'.code.toByte()
        put(257, "ustar\u000000".toByteArray())
        val pad = ByteArray((512 - (payload.size % 512)) % 512)
        val out = ByteArrayOutputStream()
        out.write(h); out.write(payload); out.write(pad); out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** Serves [body] to every GET until closed; counts the GETs it answered. */
    private class OneShotServer(private val body: ByteArray) {
        private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        val requests = java.util.concurrent.atomic.AtomicInteger()

        fun start() {
            Thread({
                while (true) {
                    val s = try { server.accept() } catch (_: Throwable) { return@Thread }
                    Thread({ serve(s) }, "test-http").start()
                }
            }, "test-http-accept").start()
        }

        fun close() { server.close() }

        private fun serve(s: Socket) {
            s.use { sock ->
                val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                while (true) {
                    val line = reader.readLine() ?: return
                    if (line.isEmpty()) break
                }
                requests.incrementAndGet()
                val out = sock.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
                )
                out.write(body)
                out.flush()
            }
        }
    }
}
