package com.lomo.data.repository

import java.io.File

internal fun relativePathFrom(
    root: File,
    child: File,
): String? {
    val rootPath = normalizePath(root)
    val childPath = normalizePath(child)
    return when {
        childPath == rootPath -> ""
        childPath.startsWith("$rootPath/") -> childPath.removePrefix("$rootPath/")
        else -> null
    }
}

private fun normalizePath(file: File): String =
    file.absoluteFile.normalize().path.replace(File.separatorChar, '/').trimEnd('/')

internal fun isAncestor(
    ancestor: File,
    child: File,
): Boolean {
    val ancestorPath = normalizePath(ancestor)
    val childPath = normalizePath(child)
    return childPath == ancestorPath || childPath.startsWith("$ancestorPath/")
}
