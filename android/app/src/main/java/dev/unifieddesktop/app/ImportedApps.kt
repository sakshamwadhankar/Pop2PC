package dev.unifieddesktop.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * T15-3: the imported-apps store. The user imports an .exe/.msi (from their PC or a URL); the
 * file is staged under the app's own storage, its SHA-256 is recorded, and the start menu shows
 * an "Imported" section. The list is a small JSON file next to the App Center's installed store
 * (app-private storage), so it survives app restarts, link reconnects and PC switches; the
 * staged files sit in the desktop's Imported folder and never leave the phone.
 *
 * The file itself is data: a portable exe is started under the shipped Wine loader, an MSI is
 * handed to the prefix's msiexec with its normal UI so the user clicks through it on the
 * streamed desktop (never silently). Nothing here auto-runs an import: a launch always comes
 * from a user tapping its row.
 */
object ImportedApps {

    const val KIND_EXE = "exe"
    const val KIND_MSI = "msi"
    const val KIND_ZIP = "zip"
    const val KIND_OTHER = "other"

    data class ImportedApp(
        val name: String,
        /** Staged file, relative to filesDir (e.g. "desktop/Imported/7zFM.exe"). */
        val file: String,
        val sha256: String,
        val dateMs: Long,
        val kind: String,
        /** "pc" or "url": where the bytes came from. */
        val source: String,
        /** For a zip import: the executable found inside, relative to filesDir ("" if none). */
        val exe: String = "",
    )

    data class StageResult(
        val entry: ImportedApp?,
        val created: Boolean,
        val message: String,
    )

    // ------------------------------------------------------------------ storage

    fun dir(ctx: Context): File = File(ctx.filesDir, "desktop/Imported").also { it.mkdirs() }

    /** Download landing zone for "Import from URL" (moved into [dir] once complete). */
    fun tmpDir(ctx: Context): File = File(ctx.filesDir, "import-tmp").also { it.mkdirs() }

    fun listFile(ctx: Context): File = File(ctx.filesDir, "appcenter/imported.json")

    fun read(ctx: Context): MutableList<ImportedApp> = readFile(listFile(ctx))

    fun readFile(f: File): MutableList<ImportedApp> {
        val out = mutableListOf<ImportedApp>()
        if (!f.isFile) return out
        return try {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("apps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val name = e.optString("name")
                val file = e.optString("file")
                if (name.isEmpty() || file.isEmpty()) continue
                out.add(
                    ImportedApp(
                        name = name,
                        file = file,
                        sha256 = e.optString("sha256"),
                        dateMs = e.optLong("dateMs"),
                        kind = e.optString("kind", KIND_OTHER),
                        source = e.optString("source", ""),
                        exe = e.optString("exe", ""),
                    ),
                )
            }
            out
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    fun write(ctx: Context, all: List<ImportedApp>) = writeFile(listFile(ctx), all)

    fun writeFile(f: File, all: List<ImportedApp>) {
        val arr = JSONArray()
        for (a in all) {
            arr.put(
                JSONObject()
                    .put("name", a.name)
                    .put("file", a.file)
                    .put("sha256", a.sha256)
                    .put("dateMs", a.dateMs)
                    .put("kind", a.kind)
                    .put("source", a.source)
                    .put("exe", a.exe),
            )
        }
        f.parentFile?.mkdirs()
        f.writeText(JSONObject().put("apps", arr).toString())
    }

    // ------------------------------------------------------------------ pure decisions

    /** The import kind from a file name; the list only distinguishes what it can launch. */
    fun kindOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "exe" -> KIND_EXE
        "msi" -> KIND_MSI
        "zip" -> KIND_ZIP
        else -> KIND_OTHER
    }

    /** The user-facing name: the file name without its extension. */
    fun displayName(fileName: String): String {
        val base = FileNames.safe(fileName)
        val dot = base.lastIndexOf('.')
        return (if (dot > 0) base.substring(0, dot) else base).ifBlank { base }
    }

    /** Same bytes already imported? The list keeps one row per SHA-256. */
    fun duplicateOf(all: List<ImportedApp>, sha256: String): ImportedApp? =
        all.firstOrNull { it.sha256.isNotEmpty() && it.sha256.equals(sha256, ignoreCase = true) }

    enum class Launch { EXE, MSI, ZIP_EXE, UNSUPPORTED }

    /** What a tap on this row runs. Pure so the msi-vs-exe rule is unit-tested. */
    fun launchFor(entry: ImportedApp): Launch = when {
        entry.kind == KIND_EXE -> Launch.EXE
        entry.kind == KIND_MSI -> Launch.MSI
        entry.kind == KIND_ZIP && entry.exe.isNotEmpty() -> Launch.ZIP_EXE
        else -> Launch.UNSUPPORTED
    }

    /** A plain status line for the import action. */
    fun statusLine(r: StageResult): String = r.message

    // ------------------------------------------------------------------ staging

    /**
     * Stage [src] as an import. The file is moved into [dir] (same storage), its SHA-256 is
     * computed, and a duplicate SHA-256 keeps the existing row (the new copy is discarded) so
     * importing the same installer twice never doubles the menu.
     */
    fun stage(ctx: Context, src: File, source: String): StageResult {
        if (!src.isFile) return StageResult(null, false, "no file to import: ${src.name}")
        val all = read(ctx)
        val sha = Phase11Files.sha256(src).joinToString("") { "%02x".format(it) }
        duplicateOf(all, sha)?.let { old ->
            // Same bytes already imported: keep one row and one staged copy (the incoming
            // duplicate is deleted even when it already sits in the import folder).
            if (src.path != File(ctx.filesDir, old.file).path) src.delete()
            return StageResult(old, false, "already imported: ${old.name}")
        }
        val name = UniqueNames.forDir(dir(ctx), FileNames.safe(src.name))
        val dest = File(dir(ctx), name)
        // The link service hands us the file already inside the import folder (kind=IMPORT
        // receipts); keep its name in that case - a rename here would point the list at a
        // file that was never written.
        val inStage = src.parentFile?.canonicalPath == dir(ctx).canonicalPath
        val finalName = if (inStage) src.name else name
        val finalFile = File(dir(ctx), finalName)
        if (!inStage) {
            src.copyTo(dest, overwrite = false)
            src.delete()
        }
        val kind = kindOf(finalName)
        var exeRel = ""
        if (kind == KIND_ZIP) {
            exeRel = extractZipToExe(ctx, finalFile) ?: ""
        }
        val rel = finalFile.relativeTo(ctx.filesDir).path
        val entry = ImportedApp(
            name = displayName(finalName),
            file = rel,
            sha256 = sha,
            dateMs = System.currentTimeMillis(),
            kind = kind,
            source = source,
            exe = exeRel,
        )
        all.add(entry)
        write(ctx, all)
        val what = when {
            kind == KIND_EXE -> "ready to run"
            kind == KIND_MSI -> "installer ready"
            kind == KIND_ZIP && exeRel.isNotEmpty() -> "unpacked: ${File(exeRel).name}"
            kind == KIND_ZIP -> "stored (no .exe found inside)"
            else -> "stored (not runnable)"
        }
        return StageResult(entry, true, "imported ${entry.name} ($what)")
    }

    /**
     * Import from a URL: fetch with the proven Range/resume path into the tmp dir, then stage.
     * Runs on a worker thread (network); the caller posts the status back to the UI.
     */
    fun importFromUrl(ctx: Context, url: String, log: (String) -> Unit = { }): StageResult {
        val u = url.trim()
        if (u.isEmpty()) return StageResult(null, false, "no URL given")
        if (!u.startsWith("https://") && !u.startsWith("http://")) {
            return StageResult(null, false, "the link must start with http:// or https://")
        }
        val name = urlFileName(u)
        val tmp = File(tmpDir(ctx), name)
        try {
            Phase2Data.downloadAny(tmpDir(ctx), u, name, log = { log(it) })
        } catch (t: Throwable) {
            return StageResult(null, false, "download failed: ${t.javaClass.simpleName}: ${t.message?.take(90)}")
        }
        val r = stage(ctx, tmp, "url")
        return r
    }

    /** The file name a URL names, sanitized; "download" when the path has none. */
    fun urlFileName(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val clean = FileNames.safe(path.ifBlank { "download.exe" })
        return if (clean.contains('.')) clean else "$clean.exe"
    }

    fun remove(ctx: Context, entry: ImportedApp) {
        val all = read(ctx)
        all.removeAll { it.sha256 == entry.sha256 }
        write(ctx, all)
        if (entry.kind == KIND_ZIP && entry.exe.isNotEmpty()) {
            File(ctx.filesDir, entry.exe).parentFile?.deleteRecursively()
        }
        File(ctx.filesDir, entry.file).delete()
    }

    // ------------------------------------------------------------------ zip

    /**
     * Extract an imported zip into Imported/<stem>/ and return the relative path of the one
     * executable inside (the app's entry point). Null when there is no .exe or more than one
     * (ambiguous - the user gets an honest "store only" row, not a guess).
     */
    fun extractZipToExe(ctx: Context, zip: File): String? {
        val stem = zip.name.substringBeforeLast('.').ifBlank { "archive" }
        val dest = File(dir(ctx), stem).also { it.mkdirs() }
        val names = extractZip(zip, dest)
        val exe = zipEntryPoint(names) ?: return null
        return File(dest, exe).relativeTo(ctx.filesDir).path
    }

    /** The single .exe in the archive (root or one folder deep), or null when 0 or 2+. */
    fun zipEntryPoint(entries: List<String>): String? {
        val exes = entries.filter { it.substringAfterLast('.', "").equals("exe", ignoreCase = true) }
            .filter { !it.contains("..") && !it.startsWith("/") }
        // The entry point is the archive's root exe when there is exactly one (the Notepad++
        // portable zip also carries updater/GUP.exe, and must still pick notepad++.exe).
        val root = exes.filter { !it.contains('/') }
        if (root.size == 1) return root[0]
        if (root.size > 1) return null
        val shallow = exes.filter { it.count { c -> c == '/' } == 1 }
        if (shallow.size == 1) return shallow[0]
        return if (exes.size == 1) exes[0] else null
    }

    /** Extract every entry of [zip] into [dest], rejecting absolute/traversal names. */
    fun extractZip(zip: File, dest: File): List<String> {
        val out = mutableListOf<String>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val raw = e.name.replace('\\', '/')
                if (raw.startsWith("/") || raw.contains("..")) continue
                val target = File(dest, raw)
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                target.outputStream().use { o -> z.copyTo(o) }
                out.add(raw)
            }
        }
        return out
    }
}

/** "name.dll", "name (2).dll" ... - staging never overwrites an earlier import. */
object UniqueNames {
    fun forDir(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (n < 10_000) {
            val cand = "$stem ($n)$ext"
            if (!File(dir, cand).exists()) return cand
            n++
        }
        return "$stem (overflow)$ext"
    }
}

