package dev.unifieddesktop.app

import android.content.Context
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * T15-6 item 3: the per-app launch recipe - the exact command line + environment
 * a chosen Linux app runs with on the PopToPC desktop.
 *
 * Pure part ([plan]): AppSpec + SysrootSpec + a [LinuxRuntime.Decision] in,
 * LaunchPlan out. Every env var and argv word is decided here so the unit tests
 * pin the whole contract, exactly like ChangePropertyWire/KeyRoute.
 *
 * Environment for every mode:
 *   HOME            - the guest home dir (files/linux/home)
 *   TMPDIR          - files/linux/tmp (our uid cannot write /tmp)
 *   XDG_CONFIG_HOME / XDG_DATA_HOME / XDG_CACHE_HOME - under the guest home
 *   PATH            - the app's own dir + the sysroot busybox bin + <home>/bin
 *   DISPLAY         - ONLY for GUI apps: 127.0.0.1:0, the existing x86-64 Xvfb
 *                     (Phase2X11). X clients speak the same wire protocol
 *                     regardless of their own instruction set.
 *
 * Command per mode (see LinuxRuntime's header for what each mode needs):
 *   NATIVE_ARM64 + dynamic -> "<loader>" "--library-path" "<libs>" "<exe>" ...
 *                             with libs = sysroot glibc lib dir + the app's own
 *                             lib dirs; LD_LIBRARY_PATH mirrors it.
 *   NATIVE_ARM64 + static  -> direct "<exe>" ... (no loader, no glibc needed).
 *   BOX64_X86_64           -> "<libbox64.so>" "<exe>" ..., the proven G3 model
 *                             (box64 from nativeLibraryDir is the only executed
 *                             file; the ELF is data), with the x86-64 glibc libs
 *                             (the shipped win86 rootfs tree) on LD_LIBRARY_PATH
 *                             AND BOX64_LD_LIBRARY_PATH + BOX64_PREFER_EMULATED=1.
 *   UNSUPPORTED            -> plan refuses (IllegalArgumentException with the
 *                             chooser's reason) - never a half plan.
 *
 * ON-DEVICE note (the honest constraint, also in LinuxRuntime): Android refuses
 * execve of app-writable files, so the NATIVE_ARM64 commands above work on a
 * standard Linux but are denied on the phone until a shipped loader (an
 * ld-linux built as a lib*.so) or another approved exec path exists. [runHarness]
 * deliberately emits the denial log line for the T15-6 phone recipe to capture.
 */
object LinuxLaunch {
    const val TAG = "Linux"

    /** The existing Xvfb (Phase2X11), TCP-only, port 6000. */
    const val DEFAULT_DISPLAY = "127.0.0.1:0"

    data class AppSpec(
        val name: String,
        /** Path of the ELF relative to the sysroot root, e.g. "firefox/firefox". */
        val exeRel: String,
        val args: List<String> = emptyList(),
        /** GUI apps get DISPLAY; headless CLI apps do not. */
        val gui: Boolean = false,
        /** Extra LD_LIBRARY_PATH dirs relative to the sysroot root (the app's own libs). */
        val libDirsRel: List<String> = emptyList(),
    )

    data class SysrootSpec(
        /** The sysroot root (files/linux/sysroot, or any tree - the x86 path points
         *  it at the win86 rootfs for the proven xeyes proof). */
        val root: File,
        /** glibc lib dir relative to root ("glibc/lib/aarch64-linux-gnu"). */
        val glibcLibRel: String,
        /** The arm64 loader relative to root ("glibc/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"). */
        val loaderRel: String,
        /** busybox bin dir relative to root (on PATH). */
        val busyboxBinRel: String,
        /** Absolute x86-64 lib dirs for BOX64 mode (the win86 rootfs tree). */
        val x86LibDirs: List<String>,
        /** The libbox64.so that is executed for BOX64 mode (from nativeLibraryDir). */
        val box64: File,
        val home: File,
        val tmp: File,
    )

    data class LaunchPlan(
        val command: List<String>,
        val env: Map<String, String>,
    )

    fun plan(app: AppSpec, sys: SysrootSpec, decision: LinuxRuntime.Decision,
             display: String = DEFAULT_DISPLAY): LaunchPlan {
        require(app.exeRel.isNotEmpty() && !app.exeRel.startsWith("/") && !app.exeRel.contains("..")) {
            "bad exeRel ${app.exeRel}"
        }
        val exe = File(sys.root, app.exeRel)
        val home = sys.home
        val env = mutableMapOf(
            "HOME" to home.path,
            "TMPDIR" to sys.tmp.path,
            "XDG_CONFIG_HOME" to File(home, ".config").path,
            "XDG_DATA_HOME" to File(home, ".local/share").path,
            "XDG_CACHE_HOME" to File(home, ".cache").path,
            // The app's own dir first (its ELF lives there), then busybox, then home/bin.
            "PATH" to listOf(
                appDir(app, sys), File(sys.root, sys.busyboxBinRel).path, File(home, "bin").path,
            ).joinToString(":"),
        )
        if (app.gui) env["DISPLAY"] = display
        return when (decision.mode) {
            LinuxRuntime.Mode.NATIVE_ARM64 -> {
                val libs = libraryPath(app, sys, listOf(File(sys.root, sys.glibcLibRel).path))
                env["LD_LIBRARY_PATH"] = libs
                val command = if (decision.dynamic) {
                    // The sysroot loader starts the binary; the loader itself reads
                    // the ELF as data. (On-device exec of the loader is the documented
                    // open constraint - see the header note.)
                    val loader = File(sys.root, sys.loaderRel)
                    listOf(loader.path, "--library-path", libs, exe.path) + app.args
                } else {
                    listOf(exe.path) + app.args
                }
                LaunchPlan(command, env)
            }
            LinuxRuntime.Mode.BOX64_X86_64 -> {
                val libs = libraryPath(app, sys, sys.x86LibDirs)
                env["LD_LIBRARY_PATH"] = libs
                env["BOX64_LD_LIBRARY_PATH"] = libs
                env["BOX64_PREFER_EMULATED"] = "1"
                LaunchPlan(listOf(sys.box64.path, exe.path) + app.args, env)
            }
            LinuxRuntime.Mode.UNSUPPORTED ->
                throw IllegalArgumentException("unsupported ELF: ${decision.reason}")
        }
    }

    /** The app's directory (PATH head): the exeRel's parent, or the root itself. */
    private fun appDir(app: AppSpec, sys: SysrootSpec): String {
        val i = app.exeRel.lastIndexOf('/')
        return if (i <= 0) sys.root.path else File(sys.root, app.exeRel.substring(0, i)).path
    }

    /** library path = the app's own lib dirs (sysroot-relative) first, then [bases]. */
    private fun libraryPath(app: AppSpec, sys: SysrootSpec, bases: List<String>): String =
        (app.libDirsRel.map { File(sys.root, it).path } + bases).distinct().joinToString(":")

    // --------------------------------------------------- the phone recipe runner
    //
    // The minimal non-pure bridge the live-phone recipe (RESULT.txt) drives: build
    // the plan from the real fetched sysroot, start it, and log what happened.
    // Expected log lines are documented in Temp\opencode\t15-6\RESULT.txt.

    private val log = StringBuilder()
    fun say(s: String) { android.util.Log.i(TAG, s); log.append(s).append('\n') }
    fun logText(): String = log.toString()

    fun writeLog(ctx: Context) {
        try {
            val dir = LinuxSysroot.linuxDir(ctx); dir.mkdirs()
            java.io.FileOutputStream(File(dir, "launch.log")).use {
                it.write(logText().toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {}
    }

    /** The default sysroot layout produced by [LinuxSysroot.ensure]. */
    fun defaultSysroot(ctx: Context, x86Mode: Boolean = false): SysrootSpec {
        val r = LinuxSysroot.sysrootDir(ctx)
        return if (x86Mode) {
            // The proven x86_64 arrangement: the win86 rootfs tree IS the sysroot,
            // its library dirs are the x86 lib dirs, box64 is the runner.
            SysrootSpec(
                root = Phase2Data.rootfsDir(ctx),
                glibcLibRel = "",
                loaderRel = "",
                busyboxBinRel = "usr/bin",
                x86LibDirs = Phase2Data.ldPaths(ctx).split(":"),
                box64 = Phase2Box64.libbox64(ctx),
                home = Phase2X11.homeDir(ctx),
                tmp = Phase2X11.tmpDir(ctx),
            )
        } else {
            SysrootSpec(
                root = r,
                glibcLibRel = "glibc/lib/aarch64-linux-gnu",
                loaderRel = "glibc/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
                busyboxBinRel = "busybox/bin",
                x86LibDirs = emptyList(),
                box64 = Phase2Box64.libbox64(ctx),
                home = File(LinuxSysroot.linuxDir(ctx), "home").apply { mkdirs() },
                tmp = File(LinuxSysroot.linuxDir(ctx), "tmp").apply { mkdirs() },
            )
        }
    }

    /** The harness demo apps. Keys: busybox (arm64 static CLI), firefox (the ARM64
     *  demo target), x86xeyes (the proven x86_64 route, run through this planner). */
    fun app(key: String): AppSpec = when (key) {
        "busybox" -> AppSpec("busybox", "busybox/bin/busybox", args = listOf("echo", "linux-sysroot-ok"))
        "firefox" -> AppSpec("firefox", "firefox/firefox", gui = true, libDirsRel = emptyList())
        "x86xeyes" -> AppSpec("x86xeyes", "usr/bin/xeyes", gui = true, libDirsRel = listOf("usr/lib/x86_64-linux-gnu"))
        else -> throw IllegalArgumentException("unknown linux app [$key]")
    }

    /**
     * Harness action: plan + start [key], log pid + the G3 /proc/<pid>/exe check,
     * drain output, bounded-wait [waitMs]. Returns when the process exited, was
     * destroyed after [waitMs], or the start was refused (each logged, never thrown).
     */
    fun runHarness(ctx: Context, key: String, waitMs: Long = 15_000) {
        try {
            val x86 = key == "x86xeyes"
            val appSpec = app(key)
            val sys = defaultSysroot(ctx, x86Mode = x86)
            val elfBytes = File(sys.root, appSpec.exeRel).readBytes()
            val decision = LinuxRuntime.choose(elfBytes)
            say("LINUXRUN $key mode=${decision.mode} dynamic=${decision.dynamic} interp=${decision.interp}")
            val p = plan(appSpec, sys, decision)
            say("LINUXRUN $key cmd=${p.command.joinToString(" ")}")
            say("LINUXRUN $key env=${p.env.entries.joinToString(",") { "${it.key}=${it.value.take(120)}" }}")
            val pb = ProcessBuilder(p.command).apply {
                environment().clear(); environment().putAll(p.env)
                redirectErrorStream(true)
            }
            val proc = pb.start()
            say("LINUXRUN $key started")
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val drain = Thread { reader.forEachLine { say("LINUXRUN_OUT $key ${it.trim()}") } }
            drain.start()
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline && proc.isAlive) Thread.sleep(200)
            if (proc.isAlive) { proc.destroy(); say("LINUXRUN $key STILL_RUNNING after ${waitMs}ms -> destroyed") }
            val code = proc.waitFor()
            drain.join(2000)
            say("LINUXRUN $key exit=$code")
        } catch (t: Throwable) {
            // The NATIVE_ARM64 exec denial lands HERE on the phone (Permission
            // denied) - that line is the recipe's deliberate negative proof.
            say("LINUXRUN $key FAILED ${t.javaClass.simpleName}: ${t.message?.take(200)}")
        }
        LinuxSysroot.writeLog(ctx)
        writeLog(ctx)
    }
}
