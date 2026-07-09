package com.lomo.data.repository

/**
 * Rejects memo [dateKey]s that would escape or alias workspace filenames when composed into
 * "<dateKey>.md". Applied at every mutation entry point so tainted remote-sync input cannot
 * drive DirectStorageBackend / SAF writes outside the workspace.
 */
internal fun requireSafeMemoDateKey(dateKey: String) {
    if (!isSafeMemoDateKey(dateKey)) {
        throw UnsafeWorkspaceMutationException("Unsafe memo dateKey: $dateKey")
    }
}

internal fun isSafeMemoDateKey(dateKey: String): Boolean {
    if (dateKey.isBlank()) return false
    if (dateKey.length > MAX_DATE_KEY_LENGTH) return false
    if (dateKey == "." || dateKey == "..") return false
    if (dateKey.startsWith(".")) return false
    return dateKey.none { ch -> ch == '/' || ch == '\\' || ch == '\u0000' }
}

/**
 * Validates a filename in the form "<dateKey>.md" (the single shape the memo workspace uses).
 * Any other extension or an unsafe dateKey component is rejected.
 */
internal fun requireSafeMemoMarkdownFilename(filename: String) {
    val dateKey = filename.removeSuffix(".md")
    if (dateKey == filename || !isSafeMemoDateKey(dateKey)) {
        throw UnsafeWorkspaceMutationException("Unsafe memo filename: $filename")
    }
}

private const val MAX_DATE_KEY_LENGTH = 64
