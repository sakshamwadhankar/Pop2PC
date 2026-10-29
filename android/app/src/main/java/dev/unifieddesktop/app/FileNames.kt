package dev.unifieddesktop.app

/**
 * T-006: the one name rule for the desktop Files window. Pure so the create/rename rules
 * are unit-tested without a desktop; the old prompt wrote `name + "-renamed"`, which broke
 * file extensions, and a name typed by the user must never escape the desktop folder.
 */
object FileNames {

    /** A trimmed name that is safe as a direct child of the desktop folder, or null. */
    fun clean(raw: String): String? {
        val n = raw.trim()
        if (n.isEmpty() || n == "." || n == "..") return null
        if (n.any { it == '/' || it == '\\' || it == '\u0000' }) return null
        return n
    }

    /** "New folder", then "New folder 2", ... skipping any name already present. */
    fun uniqueFolder(existing: Set<String>): String {
        if ("New folder" !in existing) return "New folder"
        var i = 2
        while ("New folder $i" in existing) i++
        return "New folder $i"
    }
}
