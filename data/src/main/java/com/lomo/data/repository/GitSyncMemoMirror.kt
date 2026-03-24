package com.lomo.data.repository

import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitSyncMemoMirror
    @Inject
    constructor(
        private val runtime: GitSyncRepositoryContext,
    ) {
        suspend fun mirrorMemoToRepo(
            repoDir: File,
            layout: SyncDirectoryLayout,
        ) {
            if (layout.allSameDirectory) return
            withContext(Dispatchers.IO) {
                val memoFiles =
                    runtime.markdownStorageDataSource
                        .listMetadataIn(MemoDirectoryType.MAIN)
                        .filter { it.filename.isMarkdownMemoFile() }

                val memoSubDir = File(repoDir, layout.memoFolder).also { directory ->
                    if (!directory.exists()) {
                        directory.mkdirs()
                    }
                }

                val repoMemoNames =
                    memoFiles
                        .mapNotNull { meta ->
                            syncRepoMemoFile(
                                dataSource = runtime.markdownStorageDataSource,
                                meta = meta,
                                memoSubDir = memoSubDir,
                            )
                        }.toSet()
                deleteStaleRepoMemos(memoSubDir, repoMemoNames)
            }
        }

        suspend fun mirrorMemoFromRepo(
            repoDir: File,
            layout: SyncDirectoryLayout,
        ) {
            if (layout.allSameDirectory) return
            withContext(Dispatchers.IO) {
                val memoSubDir = File(repoDir, layout.memoFolder)
                if (!memoSubDir.exists()) return@withContext

                val repoMemoNames = mutableSetOf<String>()
                memoSubDir.listFiles()?.forEach { file ->
                    if (!file.isFile || !file.name.isMarkdownMemoFile()) return@forEach
                    repoMemoNames.add(file.name)
                    val content = file.readText(StandardCharsets.UTF_8)
                    runtime.markdownStorageDataSource.saveFileIn(MemoDirectoryType.MAIN, file.name, content)
                }

                runtime.markdownStorageDataSource
                    .listMetadataIn(MemoDirectoryType.MAIN)
                    .filter { it.filename.isMarkdownMemoFile() && it.filename !in repoMemoNames }
                    .forEach { meta ->
                        runtime.markdownStorageDataSource.deleteFileIn(MemoDirectoryType.MAIN, meta.filename)
                    }
            }
        }
    }

private suspend fun syncRepoMemoFile(
    dataSource: com.lomo.data.source.MarkdownStorageDataSource,
    meta: FileMetadata,
    memoSubDir: File,
): String? {
    val target = File(memoSubDir, meta.filename)
    val needsRefresh = !isUpToDateRepoMemo(target, meta)
    val content =
        if (needsRefresh) {
            dataSource.readFileIn(MemoDirectoryType.MAIN, meta.filename)
        } else {
            null
        }

    var syncedName: String? = null
    when {
        !needsRefresh -> {
            syncedName = meta.filename
        }

        content != null -> {
            target.writeText(content, StandardCharsets.UTF_8)
            if (meta.lastModified > 0L) {
                target.setLastModified(meta.lastModified)
            }
            syncedName = meta.filename
        }
    }
    return syncedName
}

private fun isUpToDateRepoMemo(
    target: File,
    meta: FileMetadata,
): Boolean = target.exists() && target.lastModified() == meta.lastModified && target.length() > 0

private fun deleteStaleRepoMemos(
    memoSubDir: File,
    repoMemoNames: Set<String>,
) {
    memoSubDir.listFiles()?.forEach { file ->
        if (file.isFile && file.name.isMarkdownMemoFile() && file.name !in repoMemoNames) {
            file.delete()
        }
    }
}

private fun String.isMarkdownMemoFile(): Boolean = endsWith(".md")
