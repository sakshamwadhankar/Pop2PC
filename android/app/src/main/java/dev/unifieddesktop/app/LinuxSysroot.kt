package dev.unifieddesktop.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * T15-6 item 2: the minimal arm64 Linux sysroot, fetched by the user's phone.
 *
 * D14 model: NOTHING here is shipped in the APK. Every component lives upstream,
 * is downloaded at run time through [Phase2Data.download] (HTTP-Range resume +
 * SHA-256 check, the T14-3-proven path), is verified before use, and unpacked
 * idempotently (skip-if-manifest-verified, the T15-2/B-137 pattern): a component
 * whose unpacked tree carries a component-manifest naming the pinned digest AND
 * whose sentinel files exist is never re-fetched.
 *
 * The pinned manifest (all three verified from the laptop 2026-11-13):
 *   busybox  - Debian bookworm busybox-static arm64 deb (a static arm64 busybox:
 *              the minimal userland CLI, no glibc needed at run time)
 *   glibc    - Debian bookworm libc6 arm64 deb: the loader ld-linux-aarch64.so.1
 *              plus the core libs (libc/libm/libdl/libpthread/libresolv/libnss_dns/
 *              librt/libutil/libanl/libBrokenLocale) a dynamic arm64 Linux binary
 *              needs to start
 *   firefox  - Mozilla's official Linux ARM64 (aarch64) tarball, the demo target:
 *              https://ftp.mozilla.org/pub/firefox/releases/160.0/linux-aarch64/
 *
 * Pure decisions (componentSkip/pathsOk/parse) are separated for unit tests;
 * the fetch wiring runs against a local raw server in LinuxSysrootTest, no
 * external network anywhere.
 */
object LinuxSysroot {
    const val TAG = "Linux"

    /** Per-component skip marker, written after a verified unpack (the B-137 pattern). */
    const val COMPONENT_MANIFEST = ".ud-sysroot-manifest"

    private val log = StringBuilder()
    fun say(s: String) { android.util.Log.i(TAG, s); log.append(s).append('\n') }
    fun logText(): String = log.toString()

    fun writeLog(ctx: Context) {
        try {
            val dir = linuxDir(ctx); dir.mkdirs()
            java.io.FileOutputStream(File(dir, "linux.log")).use {
                it.write(logText().toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {}
    }

    fun linuxDir(ctx: Context): File = File(ctx.filesDir, "linux")
    fun downloadsDir(ctx: Context): File = File(linuxDir(ctx), "downloads")
    fun sysrootDir(ctx: Context): File = File(linuxDir(ctx), "sysroot")

    // ---------------------------------------------------------------- manifest

    data class Component(
        val name: String,
        val kind: String,
        val url: String,
        val mirrorUrls: List<String>,
        val sha256: String,
        val size: Long,
        val format: String,          // deb | tar.xz | tar.gz | zip | raw
        val unpackDir: String,       // relative to sysrootDir
        val unpackedPaths: List<String>, // sentinels relative to unpackDir
    )

    data class Manifest(val components: List<Component>)

    /** The pinned upstream manifest (verified 2026-11-13; see THIRD_PARTY.md). */
    val MANIFEST_JSON: String = """
        {"version":1,"components":[
          {"name":"busybox","kind":"busybox",
           "url":"https://deb.debian.org/debian/pool/main/b/busybox/busybox-static_1.35.0-4+deb12u1+b1_arm64.deb",
           "mirrorUrls":["https://mirror.csclub.uwaterloo.ca/debian/pool/main/b/busybox/busybox-static_1.35.0-4+deb12u1+b1_arm64.deb"],
           "sha256":"732c9135564fc71337e0e05fb4da4d11e6c28c1834bce3e405e575afef2a52f5",
           "size":822236,"format":"deb","unpackDir":"busybox",
           "unpackedPaths":["bin/busybox"]},
          {"name":"glibc","kind":"glibc",
           "url":"https://deb.debian.org/debian/pool/main/g/glibc/libc6_2.36-9+deb12u14_arm64.deb",
           "mirrorUrls":["https://mirror.csclub.uwaterloo.ca/debian/pool/main/g/glibc/libc6_2.36-9+deb12u14_arm64.deb"],
           "sha256":"01f4330719fd4f65580e16ea5a0527f372fca750e8f588d26deaf09f2d3b1cf4",
           "size":2324660,"format":"deb","unpackDir":"glibc",
           "unpackedPaths":["lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
                            "lib/aarch64-linux-gnu/libc.so.6",
                            "lib/aarch64-linux-gnu/libm.so.6",
                            "lib/aarch64-linux-gnu/libdl.so.2",
                            "lib/aarch64-linux-gnu/libpthread.so.0",
                            "lib/aarch64-linux-gnu/libresolv.so.2",
                            "lib/aarch64-linux-gnu/libnss_dns.so.2",
                            "lib/aarch64-linux-gnu/librt.so.1",
                            "lib/aarch64-linux-gnu/libutil.so.1",
                            "lib/aarch64-linux-gnu/libanl.so.1",
                            "lib/aarch64-linux-gnu/libBrokenLocale.so.1"]},
          {"name":"firefox","kind":"app",
           "url":"https://ftp.mozilla.org/pub/firefox/releases/160.0/linux-aarch64/en-US/firefox-160.0.tar.xz",
           "mirrorUrls":["https://download-installer.cdn.mozilla.net/pub/firefox/releases/160.0/linux-aarch64/en-US/firefox-160.0.tar.xz"],
           "sha256":"0fd9e4fd7ab97f7b1eec42f70e809080679498ebabd78fd692f0b94f9b6bf92f",
           "size":81548980,"format":"tar.xz","unpackDir":"firefox",
           "unpackedPaths":["firefox/firefox"]}
        ]}
    """.trimIndent()

    /** Pure parse (unit-tested): the JSON the app reads; malformed input throws. */
    fun parseManifest(json: String): Manifest {
        val root = JSONObject(json)
        val comps = root.getJSONArray("components")
        val out = ArrayList<Component>(comps.length())
        for (i in 0 until comps.length()) {
            out.add(parseComponent(comps.getJSONObject(i)))
        }
        require(out.isNotEmpty()) { "manifest has no components" }
        return Manifest(out)
    }

    fun parseComponent(o: JSONObject): Component {
        val sha = o.getString("sha256")
        require(sha.length == 64 && sha.all { c -> c in "0123456789abcdef" }) { "bad sha256 for ${o.optString("name")}" }
        val size = o.getLong("size")
        require(size > 0) { "bad size for ${o.optString("name")}" }
        val paths = ArrayList<String>()
        val arr = o.getJSONArray("unpackedPaths")
        for (i in 0 until arr.length()) {
            val p = arr.getString(i).trim().trimEnd('/')
            require(p.isNotEmpty() && !p.startsWith("/") && !p.contains("..")) { "bad unpackedPath '$p'" }
            paths.add(p)
        }
        require(paths.isNotEmpty()) { "no unpackedPaths for ${o.optString("name")}" }
        val mirrors = ArrayList<String>()
        val marr = o.optJSONArray("mirrorUrls")
        if (marr != null) for (i in 0 until marr.length()) {
            val u = marr.getString(i).trim()
            if (u.isNotEmpty()) mirrors.add(u)
        }
        return Component(
            name = o.getString("name"),
            kind = o.optString("kind", "app"),
            url = o.getString("url"),
            mirrorUrls = mirrors,
            sha256 = sha,
            size = size,
            format = o.getString("format"),
            unpackDir = o.getString("unpackDir").trim().trimEnd('/'),
            unpackedPaths = paths,
        )
    }

    /** The mirror ladder for one component: primary first, then mirrors (B-137 pattern). */
    fun urlsFor(c: Component): List<String> =
        (listOf(c.url) + c.mirrorUrls).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    // ------------------------------------------------------- pure skip decisions

    /** Pure (unit-tested): the unpacked tree is trusted only when it exists, its
     *  component manifest names the pinned digest, and every sentinel path exists. */
    fun componentSkip(
        manifestSha: String?,
        expectedSha: String,
        sentinelsOk: Boolean,
        present: Boolean,
    ): Boolean = present && sentinelsOk && manifestSha != null && manifestSha == expectedSha

    /** Pure (unit-tested): every sentinel file must exist. */
    fun pathsOk(existing: List<Boolean>): Boolean = existing.isNotEmpty() && existing.all { it }

    /** sha256 text of a component manifest file (the B-137 line format). */
    fun componentManifestText(sha: String): String = "sha256=$sha\n"

    fun parseComponentManifest(text: String?): String? {
        val v = text?.lineSequence()?.map { it.trim() }
            ?.firstOrNull { it.startsWith("sha256=") }
            ?.substringAfter('=')?.trim()
        return v?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
    }

    /**
     * Is [comp]'s unpacked tree under [sysroot] already good? Reads the component
     * manifest file and checks the sentinel paths; pure decisions above.
     */
    internal fun componentVerified(
        sysroot: File,
        comp: Component,
        log: (String) -> Unit = { say(it) },
    ): Boolean {
        val dir = File(sysroot, comp.unpackDir)
        if (!dir.isDirectory) return false
        val manifestSha = try {
            parseComponentManifest(File(dir, COMPONENT_MANIFEST).takeIf { it.isFile }?.readText())
        } catch (_: Throwable) { null }
        val sentinels = comp.unpackedPaths.map { File(dir, it).isFile }
        val ok = componentSkip(manifestSha, comp.sha256, pathsOk(sentinels), sentinels.isNotEmpty())
        if (ok) {
            log("SYSROOT ${comp.name} skip tree verified sha=${comp.sha256.take(16)}e")
        } else {
            log("SYSROOT ${comp.name} tree absent/incomplete manifest=${manifestSha?.take(16) ?: "none"} " +
                "sentinels=${sentinels.count { it }}/${sentinels.size} -> fetch")
        }
        return ok
    }

    // ------------------------------------------------------------- fetch + unpack

    /**
     * Bring every component of [manifest] into [sysroot], resumable + sha-verified.
     * Returns false when any component failed; [ensure] aborts the plan then.
     * [urlFor] lets the JVM test point a component at the local test server.
     */
    fun ensureTree(
        manifest: Manifest,
        downloads: File,
        sysroot: File,
        urlFor: (Component) -> List<String> = ::urlsFor,
        log: (String) -> Unit = { say(it) },
        progress: ((Long, Long) -> Unit)? = null,
    ): Boolean {
        downloads.mkdirs(); sysroot.mkdirs()
        var ok = true
        for (comp in manifest.components) {
            try {
                ensureComponent(comp, downloads, sysroot, urlFor(comp), log, progress)
            } catch (t: Throwable) {
                ok = false
                log("SYSROOT ${comp.name} FAILED ${t.javaClass.simpleName}: ${t.message?.take(160)}")
            }
        }
        return ok
    }

    /** Fetch + unpack ONE component idempotently (skip-if-manifest-verified). */
    internal fun ensureComponent(
        comp: Component,
        downloads: File,
        sysroot: File,
        urls: List<String>,
        log: (String) -> Unit = { say(it) },
        progress: ((Long, Long) -> Unit)? = null,
    ) {
        if (componentVerified(sysroot, comp, log)) return
        val t0 = System.currentTimeMillis()
        val file = Phase2Data.downloadWithMirrors(downloads, urls, comp.sha256, fileName(comp), log, progress)
        val got = file.length()
        check(got == comp.size) { "${comp.name}: fetched ${got} B, manifest pins ${comp.size} B" }
        val target = File(sysroot, comp.unpackDir)
        target.mkdirs()
        unpack(file, comp.format, target, comp.name, log)
        // Mark only after the sentinels really exist (a bad unpack never gets trusted).
        val sentinels = comp.unpackedPaths.map { File(target, it).isFile }
        check(pathsOk(sentinels)) {
            "${comp.name}: unpacked tree misses sentinels " +
                comp.unpackedPaths.filterIndexed { i, _ -> !sentinels[i] }
        }
        try {
            File(target, COMPONENT_MANIFEST).writeText(componentManifestText(comp.sha256))
        } catch (t: Throwable) {
            log("SYSROOT ${comp.name} manifest write failed ${t.javaClass.simpleName}")
        }
        log("SYSROOT ${comp.name} unpacked ms=${System.currentTimeMillis() - t0} into ${comp.unpackDir}")
    }

    /** Download cache name: last path segment of the primary URL. */
    fun fileName(comp: Component): String = comp.url.substringAfterLast('/')

    private fun unpack(file: File, format: String, target: File, name: String, log: (String) -> Unit) {
        when (format) {
            "deb" -> Phase2Data.unpackDeb(file, target)
            "tar.xz" -> XZInputStream(BufferedInputStream(FileInputStream(file))).use {
                Phase2Data.readTar(it, target, "$name.tar")
            }
            "tar.gz" -> GZIPInputStream(BufferedInputStream(FileInputStream(file))).use {
                Phase2Data.readTar(it, target, "$name.tar")
            }
            "zip" -> ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val f = File(target, safeEntryName(e.name))
                        f.parentFile?.mkdirs()
                        java.io.FileOutputStream(f).use { zin.copyTo(it) }
                    }
                    zin.closeEntry(); e = zin.nextEntry
                }
            }
            "raw" -> {
                val f = File(target, file.name)
                java.io.FileOutputStream(f).use { out ->
                    FileInputStream(file).use { src -> src.copyTo(out) }
                }
            }
            else -> throw IllegalStateException("$name: unknown format $format")
        }
        log("SYSROOT $name unpacked format=$format")
    }

    /** Zip entry names (unlike tar members) are validated like Phase2Data.San: no
     *  absolute paths, no "..", no "./" noise. Throws on a traversal attempt. */
    private fun safeEntryName(raw: String): String {
        val n = raw.removePrefix("./").trimEnd('/')
        require(n.isNotEmpty() && !n.startsWith("/") && !n.contains("..")) { "bad zip name $raw" }
        return n
    }

    // ------------------------------------------------------------------- entry

    /**
     * Product-path bootstrap: fetch + unpack the whole pinned manifest under
     * files/linux. Returns false (and logs) if any component failed; callers
     * abort the launch. @Synchronized like ensureWineRuntime: two threads must
     * never unpack the same tree at once.
     */
    @Synchronized
    fun ensure(ctx: Context, progress: ((Long, Long) -> Unit)? = null): Boolean {
        val t0 = System.currentTimeMillis()
        val manifest = parseManifest(MANIFEST_JSON)
        val ok = ensureTree(manifest, downloadsDir(ctx), sysrootDir(ctx), progress = progress)
        say("SYSROOT ensure ok=$ok ms=${System.currentTimeMillis() - t0}")
        writeLog(ctx)
        return ok
    }
}
