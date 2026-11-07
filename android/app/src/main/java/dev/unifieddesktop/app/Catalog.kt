package dev.unifieddesktop.app

import android.content.Context
import org.json.JSONObject

/**
 * One version of one app in the App Center catalog, as the picker sees it. Pure data: the
 * JVM tests build these directly, so no org.json is needed to test the picker.
 *
 * [rating] is how well this version runs on the phone (the only thing that decides whether
 * it is offered), never whether an installer exists. [fetch] says where the bytes come from.
 *
 * Catalog refresh policy — upstream re-released under the same tag (B-133, manager-approved
 * 2026-11-07): a publisher may replace a release asset WITHOUT changing its tag or version,
 * so the bytes at a pinned URL can change under an unchanged manifest. Notepad++ 8.9.8.1 did
 * exactly that (twice: the MSI briefly served different bytes, then the original bytes came
 * back). On a sha mismatch the check itself is NEVER downgraded or skipped; instead:
 * (1) fetch the CURRENT bytes at the pinned URL and compute sha256; (2) if they match a
 * winget manifest commit whose InstallerSha256 equals those bytes, re-pin [fetch] to that
 * commit and [sha256] to it; (3) if the upstream bytes are unstable, move the entry to a
 * distribution whose hash is stable (e.g. the portable zip), verifying the new pin the same
 * way; (4) log the change (old sha -> new sha, date, evidence path) in the entry's note and
 * the task report. A mismatch is a refusal, never a silent accept: unverified bytes must
 * never reach Wine (B-081 policy).
 */
data class CatalogVersion(
    val version: String,
    val kind: String,
    val rating: String,
    val fetch: String = "",
    val sha256: String = "",
    val source: String = "",
    val packageName: String = "",
    val note: String = "",
)

data class CatalogApp(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val versions: List<CatalogVersion> = emptyList(),
    val open: String = "",
    val packageName: String = "",
)

/**
 * The catalog's pure logic (D7): among the versions that run well enough to offer, prefer the
 * best rating, then the kind order builtin > android > web > linux > windows, then the highest
 * version. "Not yet" versions are never picked while any other version is usable; they are
 * still returned when nothing works, so the App Center can show the real reason.
 */
object Catalog {

    const val WORKS = "Works"
    const val SLOW = "Works, slow"
    const val NOT_YET = "Not yet"

    val KIND_ORDER = listOf("builtin", "android", "web", "linux", "windows")

    fun ratingRank(rating: String): Int = when {
        rating.equals(WORKS, ignoreCase = true) -> 2
        rating.equals(SLOW, ignoreCase = true) -> 1
        else -> 0
    }

    fun kindRank(kind: String): Int = KIND_ORDER.indexOf(kind).let { if (it < 0) KIND_ORDER.size else it }

    fun isUsable(v: CatalogVersion): Boolean = ratingRank(v.rating) > 0

    /** Best version for [app], or null when it has none. Never null because of a missing installer. */
    fun pick(app: CatalogApp): CatalogVersion? {
        return app.versions.sortedWith(
            compareByDescending<CatalogVersion> { ratingRank(it.rating) }
                .thenBy { kindRank(it.kind) }
                .thenComparator { a, b -> compareVersions(b.version, a.version) }
        ).firstOrNull()
    }

    /** The version the App Center may install/open, or null when every version is "Not yet". */
    fun pickUsable(app: CatalogApp): CatalogVersion? = pick(app)?.takeIf { isUsable(it) }

    /** Case-insensitive search over the name and the aliases; a blank query returns all. */
    fun search(apps: List<CatalogApp>, query: String): List<CatalogApp> {
        val q = query.trim()
        if (q.isEmpty()) return apps
        return apps.filter { app ->
            app.name.contains(q, ignoreCase = true) ||
                app.aliases.any { it.contains(q, ignoreCase = true) }
        }
    }

    /** Numeric-aware version compare: 8.10 > 8.9.8.1, 4.95a > 4.95. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-', '_')
        val pb = b.split('.', '-', '_')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: ""
            val y = pb.getOrNull(i) ?: ""
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
            if (c != 0) return c
        }
        return a.compareTo(b)
    }
}

/** Reads `assets/app_catalog.json`. Android-only; the picker above is what the tests cover. */
object CatalogLoader {

    fun load(context: Context): List<CatalogApp> =
        context.assets.open("app_catalog.json").bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }

    fun parse(text: String): List<CatalogApp> {
        val apps = JSONObject(text).getJSONArray("apps")
        val out = ArrayList<CatalogApp>(apps.length())
        for (i in 0 until apps.length()) {
            val a = apps.getJSONObject(i)
            val aliases = a.optJSONArray("aliases")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val versions = a.optJSONArray("versions")?.let { arr ->
                (0 until arr.length()).map { j ->
                    val v = arr.getJSONObject(j)
                    CatalogVersion(
                        version = v.optString("version"),
                        kind = v.optString("kind"),
                        rating = v.optString("rating"),
                        fetch = v.optString("fetch"),
                        sha256 = v.optString("sha256"),
                        source = v.optString("source"),
                        packageName = v.optString("packageName").ifEmpty { a.optString("packageName") },
                        note = v.optString("note"),
                    )
                }
            } ?: emptyList()
            out.add(
                CatalogApp(
                    id = a.getString("id"),
                    name = a.getString("name"),
                    aliases = aliases,
                    versions = versions,
                    open = a.optString("open"),
                    packageName = a.optString("packageName"),
                )
            )
        }
        return out
    }
}
