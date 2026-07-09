package com.lomo.data.webdav

import android.content.Context
import com.lomo.data.sync.SyncDirectoryLayout
import java.io.File

internal fun listDirectFiles(
    root: MediaRoot.Direct,
    layout: SyncDirectoryLayout,
): List<LocalMediaSyncFile> {
    val directory = File(root.path)
    if (!directory.exists() || !directory.isDirectory) return emptyList()
    val folder = root.category.remoteFolder(layout)
    return directory
        .listFiles()
        ?.asSequence()
        ?.filter { file -> file.isFile && accepts(root.category, file.name) }
        ?.map { file ->
            LocalMediaSyncFile(
                relativePath = "$folder/${file.name}",
                lastModified = file.lastModified(),
                size = file.length(),
            )
        }?.toList()
        ?: emptyList()
}

internal fun listSafFiles(
    context: Context,
    root: MediaRoot.Saf,
    layout: SyncDirectoryLayout,
): List<LocalMediaSyncFile> {
    val directory = getSafRoot(context, root) ?: return emptyList()
    val folder = root.category.remoteFolder(layout)
    return directory.listFiles().mapNotNull { document ->
        val name = document.name ?: return@mapNotNull null
        if (!document.isFile || !accepts(root.category, name, document.type)) return@mapNotNull null
        LocalMediaSyncFile(
            relativePath = "$folder/$name",
            lastModified = document.lastModified(),
            size = document.length(),
        )
    }
}
