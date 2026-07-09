package com.lomo.data.repository

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

internal suspend fun writeWorkspaceSafFileFromStream(
    context: Context,
    category: WorkspaceMediaCategory,
    rootUriString: String,
    filename: String,
    source: suspend (OutputStream) -> Unit,
) {
    withContext(Dispatchers.IO) {
        val root = requireNotNull(resolveWorkspaceSafRoot(context, rootUriString)) { "Cannot access SAF media root" }
        val tempFilename = temporaryWorkspaceSafFilename(filename)
        val temp =
            root.createFile(workspaceMimeTypeFor(category, filename), tempFilename)
                ?: throw IOException("Cannot create temporary SAF media file: $filename")
        var committed = false
        try {
            context.contentResolver.openOutputStream(temp.uri)?.use { output ->
                source(output)
            } ?: throw IOException("Cannot open temporary SAF media output stream: $filename")
            commitWorkspaceSafFile(root = root, temp = temp, filename = filename)
            committed = true
        } finally {
            if (!committed) {
                temp.delete()
            }
        }
    }
}

private fun commitWorkspaceSafFile(
    root: DocumentFile,
    temp: DocumentFile,
    filename: String,
) {
    if (root.findFile(filename) != null) {
        throw IOException("Cannot safely replace existing SAF media file: $filename")
    }
    if (!temp.renameTo(filename)) {
        throw IOException("Cannot commit temporary SAF media file: $filename")
    }
}
