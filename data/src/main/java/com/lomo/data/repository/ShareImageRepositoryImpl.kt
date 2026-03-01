package com.lomo.data.repository

import android.content.Context
import com.lomo.domain.repository.ShareImageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ShareImageRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ShareImageRepository {
        override suspend fun storeShareImage(
            pngBytes: ByteArray,
            fileNamePrefix: String,
        ): String =
            withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, SHARED_MEMO_CACHE_DIR).apply { mkdirs() }
                ShareImageCacheCleaner.cleanup(
                    directory = directory,
                    maxFiles = SHARED_MEMO_CACHE_MAX_FILES,
                    maxAgeMs = SHARED_MEMO_CACHE_MAX_AGE_MS,
                )

                val output = File(directory, "${fileNamePrefix}_${System.currentTimeMillis()}.png")
                output.outputStream().use { out ->
                    out.write(pngBytes)
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

internal object ShareImageCacheCleaner {
    fun cleanup(
        directory: File,
        maxFiles: Int,
        maxAgeMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) {
            return
        }

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
                ?.sortedByDescending { it.lastModified() }
                ?: return

        if (remainingFiles.size <= maxFiles) {
            return
        }

        remainingFiles
            .drop(maxFiles)
            .forEach { file ->
                runCatching { file.delete() }
            }
    }
}
