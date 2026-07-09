package com.lomo.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class DirectWorkspaceConfigBackendDelegate(
    private val rootDir: File,
) : WorkspaceConfigBackend {
    override suspend fun createDirectory(name: String): String = directCreateDirectory(rootDir, name)
}

private suspend fun directCreateDirectory(
    rootDir: File,
    name: String,
): String =
    withContext(Dispatchers.IO) {
        directEnsureRootExists(rootDir)
        val dir = File(rootDir, name)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Cannot create directory $name")
        }
        dir.absolutePath
    }
