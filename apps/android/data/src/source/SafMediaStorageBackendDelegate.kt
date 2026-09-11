package com.lomo.data.source

import kotlinx.coroutines.withContext

internal class SafMediaStorageBackendDelegate(
    private val documentAccess: SafDocumentAccess,
) : MediaStorageBackend {
    override suspend fun listImageFiles(): List<Pair<String, String>> = safListImageFiles(documentAccess)

    override suspend fun getImageLocation(filename: String): String? = safGetImageLocation(documentAccess, filename)
}

private suspend fun safListImageFiles(
    documentAccess: SafDocumentAccess,
): List<Pair<String, String>> =
    withContext(SAF_IO_DISPATCHER) {
        val root = documentAccess.root() ?: return@withContext emptyList()
        root.listFiles().mapNotNull { file ->
            val name = file.name
            if (name == null || !file.isFile) {
                return@mapNotNull null
            }
            val mimeType = file.type
            if (mimeType?.startsWith("image/") == true || safIsImageFilename(name)) {
                name to file.uri.toString()
            } else {
                null
            }
        }
    }

private suspend fun safGetImageLocation(
    documentAccess: SafDocumentAccess,
    filename: String,
): String? =
    withContext(SAF_IO_DISPATCHER) {
        documentAccess.root()?.findFile(filename)?.takeIf { it.isFile }?.uri?.toString()
    }
