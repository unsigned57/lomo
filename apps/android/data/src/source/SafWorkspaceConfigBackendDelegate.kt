package com.lomo.data.source

import kotlinx.coroutines.withContext
import java.io.IOException

internal class SafWorkspaceConfigBackendDelegate(
    private val documentAccess: SafDocumentAccess,
) : WorkspaceConfigBackend {
    override suspend fun createDirectory(name: String): String = safCreateDirectory(documentAccess, name)
}

private suspend fun safCreateDirectory(
    documentAccess: SafDocumentAccess,
    name: String,
): String =
    withContext(SAF_IO_DISPATCHER) {
        val root = documentAccess.root() ?: throw IOException("Cannot access root directory")
        val dir =
            root.findFile(name)
                ?: root.createDirectory(name)
                ?: throw IOException("Cannot create directory $name")
        dir.uri.toString()
    }
