package com.lomo.data.repository

import android.content.Context
import com.lomo.domain.repository.ShareImageRepository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID


class ShareImageRepositoryImpl(
    private val context: Context,
) : ShareImageRepository {
        override suspend fun storeShareImage(
            fileNamePrefix: String,
            writer: suspend (OutputStream) -> Unit,
        ): String =
            withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, SHARED_MEMO_CACHE_DIR).apply { mkdirs() }
                ShareImageCacheCleaner.cleanup(
                    directory = directory,
                    maxFiles = SHARED_MEMO_CACHE_MAX_FILES,
                    maxAgeMs = SHARED_MEMO_CACHE_MAX_AGE_MS,
                )

                val output = File(directory, "${fileNamePrefix}_${System.currentTimeMillis()}.png")
                val temp = File(directory, "${output.name}.tmp.${UUID.randomUUID()}")
                var committed = false
                try {
                    temp.outputStream().use { out ->
                        writer(out)
                    }
                    commitShareImageTempFile(temp = temp, target = output)
                    committed = true
                } finally {
                    if (!committed) {
                        temp.delete()
                    }
                }

                ShareImageCacheCleaner.cleanup(
                    directory = directory,
                    maxFiles = SHARED_MEMO_CACHE_MAX_FILES,
                    maxAgeMs = SHARED_MEMO_CACHE_MAX_AGE_MS,
                )
                output.absolutePath
            }

        private companion object {
            const val SHARED_MEMO_CACHE_DIR = "shared_memos"
            const val SHARED_MEMO_CACHE_MAX_FILES = 40
            const val SHARED_MEMO_CACHE_MAX_AGE_MS = 48L * 60 * 60 * 1000
        }
    }

private fun commitShareImageTempFile(
    temp: File,
    target: File,
) {
    try {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

internal object ShareImageCacheCleaner {
    fun cleanup(
        directory: File,
        maxFiles: Int,
        maxAgeMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return

        val staleThreshold = nowMs - maxAgeMs
        files
            .asSequence()
            .filter { it.lastModified() < staleThreshold }
            .forEach { file ->
                runCatching { file.delete() }
            }

        val remainingFiles =
            directory
                .listFiles()
                ?.filter { it.isFile }
                .orEmpty()
                .sortedByDescending { it.lastModified() }
        remainingFiles
            .drop(maxFiles.coerceAtMost(remainingFiles.size))
            .forEach { file ->
                runCatching { file.delete() }
            }
    }
}
