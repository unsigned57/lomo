package com.lomo.data.repository

import android.content.Context
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
        root.findFile(filename)?.delete()
        val target =
            root.createFile(workspaceMimeTypeFor(category, filename), filename)
                ?: throw IOException("Cannot create SAF media file: $filename")
        context.contentResolver.openOutputStream(target.uri)?.use { output ->
            source(output)
        } ?: throw IOException("Cannot open SAF media output stream: $filename")
    }
}
