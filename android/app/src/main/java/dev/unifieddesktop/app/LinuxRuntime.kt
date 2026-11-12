package dev.unifieddesktop.app

/**
 * T15-6 item 1: the Linux runtime chooser.
 *
 * Given the bytes of a Linux ELF (a fetched-at-runtime app, D14: the phone downloads
 * it, we never ship one), decide HOW it runs on the PopToPC desktop:
 *
 *   NATIVE_ARM64  - the ELF is already arm64 (EM_AARCH64): it runs in the phone's own
 *                   instruction set, but as a LINUX glibc binary, not bionic. What it
 *                   needs at runtime:
 *                     - a loader: the sysroot's glibc loader ld-linux-aarch64.so.1
 *                       (fetched, see LinuxSysroot). A dynamic binary is started as
 *                       `ld-linux --library-path <libs> <elf>`; the sysroot glibc/lib
 *                       dir goes on that library path together with the app's own lib
 *                       dirs. A static binary needs no loader and no glibc libs.
 *                     - LD_LIBRARY_PATH for anything it dlopens, and for X11 apps
 *                       DISPLAY=127.0.0.1:0 (the existing x86-64 Xvfb - X clients
 *                       speak the same wire protocol regardless of their own arch).
 *                     - HOME/XDG_* dirs (LinuxLaunch builds all of them).
 *                     - ON-DEVICE CONSTRAINT (read before shipping): Android refuses
 *                       execve of app-writable files (untrusted_app neverexec +
 *                       targetSdk 36 W^X), and only lib*.so in nativeLibraryDir is
 *                       executable. So the direct-exec command LinuxLaunch emits for
 *                       this mode works on a standard Linux but NOT on the phone; the
 *                       on-device mechanism must be a shipped loader (an ld-linux
 *                       built as a lib*.so) or another approved exec path. The
 *                       T15-6 phone recipe proves this refusal deliberately (it IS
 *                       the evidence the follow-up task needs to pick the mechanism).
 *   BOX64_X86_64  - the ELF is x86-64 (EM_X86_64): it runs under the already-shipped
 *                   libbox64.so (argv[0] from nativeLibraryDir, the ELF read as DATA -
 *                   the proven G3 model). What it needs at runtime:
 *                     - x86-64 glibc libraries: the shipped bundle tree
 *                       (win86/rootfs/usr/lib/box64-x86_64-linux-gnu + the win86
 *                       rootfs x86-64 dirs) on LD_LIBRARY_PATH *and*
 *                       BOX64_LD_LIBRARY_PATH, plus BOX64_PREFER_EMULATED=1 like
 *                       every other box64 launch.
 *                     - DISPLAY=127.0.0.1:0 for X11 apps (same shared Xvfb).
 *   UNSUPPORTED   - malformed/not-ELF, 32-bit (no 32-bit translator is shipped;
 *                   box64 is 64-bit only), big-endian, or another machine type.
 *
 * Pure: no Android classes, no I/O - the same shape as ChangePropertyWire/KeyRoute
 * so every branch is unit-tested against tiny hand-built ELF fixtures
 * (app/src/test/resources/elf/, no downloads).
 */
object LinuxRuntime {
    enum class Mode { NATIVE_ARM64, BOX64_X86_64, UNSUPPORTED }

    /** What [choose] decided. [interp] is the PT_INTERP payload ("/lib/ld-linux-...").
     *  [reason] is non-null only for UNSUPPORTED. */
    data class Decision(
        val mode: Mode,
        val dynamic: Boolean,
        val interp: String?,
        val reason: String?,
    )

    // e_machine values (System V ABI).
    const val EM_386: Int = 3
    const val EM_X86_64: Int = 62
    const val EM_AARCH64: Int = 183

    // Program-header types.
    const val PT_LOAD: Int = 1
    const val PT_INTERP: Int = 3

    const val ELF_HDR64: Int = 64
    const val ELF_PHDR64: Int = 56

    /**
     * Parse [elf]'s ELF64 header and program-header table and pick the mode.
     * The WHOLE program-header table must be inside the bytes (we always pass a
     * fully-read binary); a header-only buffer cannot prove static vs dynamic, so
     * it is reported unsupported "truncated" rather than guessed.
     */
    fun choose(elf: ByteArray): Decision {
        // --- header ----------------------------------------------------------
        // 52 B covers the ELF32 header (the smallest legal ELF); the 64 B ELF64
        // header is required before the ELF64-only phdr parsing below.
        if (elf.size < 52) return unsupported("file shorter than an ELF header (${elf.size} B)")
        if (!(elf[0] == 0x7f.toByte() && elf[1] == 'E'.code.toByte() &&
              elf[2] == 'L'.code.toByte() && elf[3] == 'F'.code.toByte())
        ) return unsupported("not an ELF (bad magic)")
        val eiClass = elf[4].toInt() and 0xff
        if (eiClass == 1) return unsupported("32-bit ELF (EI_CLASS=1): no 32-bit translator is shipped")
        if (eiClass != 2) return unsupported("unknown EI_CLASS=$eiClass")
        val eiData = elf[5].toInt() and 0xff
        if (eiData == 2) return unsupported("big-endian ELF (EI_DATA=2) not supported")
        if (eiData != 1) return unsupported("unknown EI_DATA=$eiData")
        val machine = u16(elf, 18)
        val phoff = u64(elf, 32).toInt()
        val phentsize = u16(elf, 54)
        val phnum = u16(elf, 56)
        if (phnum == 0) return unsupported("ELF has no program headers")
        if (phentsize != ELF_PHDR64) return unsupported("unexpected e_phentsize=$phentsize")
        val phEnd = phoff.toLong() + phnum.toLong() * phentsize
        if (phoff <= 0 || phEnd > elf.size) return unsupported("truncated program headers ($phEnd B needed, ${elf.size} B present)")

        // --- program headers: find PT_INTERP ---------------------------------
        var interp: String? = null
        for (i in 0 until phnum) {
            val p = phoff + i * phentsize
            val pType = u32(elf, p)
            if (pType == PT_INTERP) {
                interp = readInterp(elf, p)
            }
        }

        // --- decide -----------------------------------------------------------
        return when (machine) {
            EM_AARCH64 -> Decision(Mode.NATIVE_ARM64, dynamic = interp != null, interp, null)
            EM_X86_64 -> Decision(Mode.BOX64_X86_64, dynamic = interp != null, interp, null)
            EM_386 -> unsupported("32-bit x86 (EM_386): box64 translates 64-bit only")
            else -> unsupported("machine type $machine not supported")
        }
    }

    private fun unsupported(reason: String) = Decision(Mode.UNSUPPORTED, dynamic = false, interp = null, reason = reason)

    private fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        u16(b, off) or (u16(b, off + 2) shl 16)

    private fun u64(b: ByteArray, off: Int): Long =
        (u32(b, off).toLong() and 0xffffffffL) or ((u32(b, off + 4).toLong() and 0xffffffffL) shl 32)

    /** The PT_INTERP payload at program header [p]: p_offset/p_filesz (fields 8 and 32),
     *  NUL-terminated UTF-8. A payload that leaves the buffer degrades to null - the
     *  binary still gets its mode from e_machine. */
    private fun readInterp(elf: ByteArray, p: Int): String? {
        val off = u64(elf, p + 8).toInt()
        val size = u64(elf, p + 32).toInt()
        if (off < 0 || size <= 0 || off.toLong() + size > elf.size) return null
        var end = off + size
        for (i in off until off + size) {
            if (elf[i].toInt() == 0) { end = i; break }
        }
        return String(elf, off, end - off, Charsets.UTF_8)
    }
}
