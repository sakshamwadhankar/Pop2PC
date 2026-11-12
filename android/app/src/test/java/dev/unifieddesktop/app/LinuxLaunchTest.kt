package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T15-6 item 3: the pure launch planner. Each mode's exact command + env is pinned,
 * with the decisions fed from the same hand-built ELF fixtures LinuxRuntimeTest uses.
 */
class LinuxLaunchTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/elf/$name")!!.use { it.readBytes() }

    /** A fake sysroot tree matching the real [LinuxSysroot] layout. */
    private fun sysroot(x86: Boolean = false, box64: File? = null): LinuxLaunch.SysrootSpec {
        val root = tmp.newFolder()
        File(root, "glibc/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").let {
            it.parentFile!!.mkdirs(); it.writeBytes(fixture("arm64-static.bin"))
        }
        File(root, "glibc/lib/aarch64-linux-gnu/libc.so.6").writeBytes(ByteArray(8))
        File(root, "busybox/bin/busybox").let { it.parentFile!!.mkdirs(); it.writeBytes(fixture("arm64-static.bin")) }
        val home = File(root, "../home").canonicalFile.also { it.mkdirs() }
        val t = File(root, "../tmp").canonicalFile.also { it.mkdirs() }
        val box = box64 ?: tmp.newFolder("nativedir").resolve("libbox64.so").apply { writeBytes(ByteArray(4)) }
        return LinuxLaunch.SysrootSpec(
            root = root,
            glibcLibRel = "glibc/lib/aarch64-linux-gnu",
            loaderRel = "glibc/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            busyboxBinRel = "busybox/bin",
            x86LibDirs = if (x86) listOf("/fake/win86/rootfs/usr/lib/x86_64-linux-gnu") else emptyList(),
            box64 = box,
            home = home,
            tmp = t,
        )
    }

    private fun decision(fixtureName: String): LinuxRuntime.Decision =
        LinuxRuntime.choose(fixture(fixtureName))

    // ------------------------------------------------------------- arm64 modes

    @Test
    fun arm64DynamicPlanStartsWithTheSysrootLoader() {
        val sys = sysroot()
        val app = LinuxLaunch.AppSpec("demo", "demo/bin/prog", args = listOf("--v"),
            gui = false, libDirsRel = listOf("demo/lib"))
        val p = LinuxLaunch.plan(app, sys, decision("arm64-dyn.bin"))
        val libs = listOf(
            File(sys.root, "demo/lib").path,
            File(sys.root, "glibc/lib/aarch64-linux-gnu").path,
        ).joinToString(":")
        assertEquals(
            listOf(
                File(sys.root, "glibc/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").path,
                "--library-path", libs, File(sys.root, "demo/bin/prog").path, "--v",
            ),
            p.command,
        )
        assertEquals("LD_LIBRARY_PATH mirrors the loader's library path", libs, p.env["LD_LIBRARY_PATH"])
        assertFalse("headless app: no DISPLAY", p.env.containsKey("DISPLAY"))
    }

    @Test
    fun arm64StaticPlanExecutesDirectly() {
        val sys = sysroot()
        val app = LinuxLaunch.AppSpec("bb", "busybox/bin/busybox", args = listOf("echo", "ok"))
        val p = LinuxLaunch.plan(app, sys, decision("arm64-static.bin"))
        assertEquals(listOf(File(sys.root, "busybox/bin/busybox").path, "echo", "ok"), p.command)
        assertFalse(p.env.containsKey("DISPLAY"))
    }

    @Test
    fun arm64GuiAppGetsTheExistingXvfbDisplay() {
        val sys = sysroot()
        val app = LinuxLaunch.AppSpec("fx", "firefox/firefox", gui = true)
        val p = LinuxLaunch.plan(app, sys, decision("arm64-dyn.bin"))
        assertEquals("127.0.0.1:0", p.env["DISPLAY"])
        // XDG dirs live under the guest home.
        assertEquals(File(sys.home, ".config").path, p.env["XDG_CONFIG_HOME"])
        assertEquals(File(sys.home, ".local/share").path, p.env["XDG_DATA_HOME"])
        assertEquals(File(sys.home, ".cache").path, p.env["XDG_CACHE_HOME"])
        assertEquals(sys.home.path, p.env["HOME"])
        assertEquals(sys.tmp.path, p.env["TMPDIR"])
    }

    @Test
    fun arm64PathPutsTheAppDirFirstThenBusyboxThenHomeBin() {
        val sys = sysroot()
        val app = LinuxLaunch.AppSpec("fx", "firefox/firefox", gui = true)
        val p = LinuxLaunch.plan(app, sys, decision("arm64-dyn.bin"))
        assertEquals(
            listOf(
                File(sys.root, "firefox").path,
                File(sys.root, "busybox/bin").path,
                File(sys.home, "bin").path,
            ).joinToString(":"),
            p.env["PATH"],
        )
    }

    // -------------------------------------------------------------- x86_64 mode

    @Test
    fun x86_64PlanRunsUnderTheShippedBox64WithTheX86LibDirs() {
        val sys = sysroot(x86 = true)
        val app = LinuxLaunch.AppSpec("xeyes", "usr/bin/xeyes", gui = true,
            libDirsRel = listOf("usr/lib/x86_64-linux-gnu"))
        val p = LinuxLaunch.plan(app, sys, decision("x86-dyn.bin"))
        assertEquals(listOf(sys.box64.path, File(sys.root, "usr/bin/xeyes").path), p.command)
        val libs = listOf(
            File(sys.root, "usr/lib/x86_64-linux-gnu").path,
            "/fake/win86/rootfs/usr/lib/x86_64-linux-gnu",
        ).joinToString(":")
        assertEquals(libs, p.env["LD_LIBRARY_PATH"])
        assertEquals("box64 needs its own library path", libs, p.env["BOX64_LD_LIBRARY_PATH"])
        assertEquals("1", p.env["BOX64_PREFER_EMULATED"])
        assertEquals("127.0.0.1:0", p.env["DISPLAY"])
    }

    // --------------------------------------------------------------- refusals

    @Test
    fun anUnsupportedDecisionIsRefusedWithTheChoosersReason() {
        val sys = sysroot()
        val app = LinuxLaunch.AppSpec("bad", "x.bin")
        try {
            LinuxLaunch.plan(app, sys, decision("i386-32bit.bin"))
            fail("plan must refuse an unsupported decision")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
            assertTrue(e.message!!.contains("32-bit"))
        }
    }

    @Test
    fun aTraversalExeRelIsRefused() {
        val sys = sysroot()
        try {
            LinuxLaunch.plan(LinuxLaunch.AppSpec("bad", "../../etc/passwd"), sys, decision("arm64-static.bin"))
            fail("exeRel traversal must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }
}
