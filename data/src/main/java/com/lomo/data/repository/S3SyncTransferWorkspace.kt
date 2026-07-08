package com.lomo.data.repository

import android.content.Context

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID


internal data class S3TransferFile(
    val file: File,
)

class S3SyncTransferWorkspace
    private constructor(
        private val rootDir: File,
    ) {
        constructor(
            context: Context,
        ) : this(File(context.cacheDir, S3_TRANSFER_WORKSPACE_DIR_NAME))

        internal suspend fun <T> withSession(block: suspend (S3SyncTransferSession) -> T): T {
            val sessionDir =
                withContext(Dispatchers.IO) {
                    rootDir.mkdirs()
                    File(rootDir, "session-${UUID.randomUUID()}").apply { mkdirs() }
                }
            return try {
                block(S3SyncTransferSession(sessionDir))
            } finally {
                withContext(Dispatchers.IO) {
                    sessionDir.deleteRecursively()
                }
            }
        }

        companion object {
            internal fun systemTemp(): S3SyncTransferWorkspace =
                S3SyncTransferWorkspace(
                    File(System.getProperty("java.io.tmpdir"), S3_TRANSFER_WORKSPACE_DIR_NAME),
                )
        }
    }

internal class S3SyncTransferSession(
    private val sessionDir: File,
) {
    fun createTempFile(
        prefix: String,
        suffix: String = ".tmp",
    ): File {
        sessionDir.mkdirs()
        return File.createTempFile(prefix, suffix, sessionDir)
    }
}

private const val S3_TRANSFER_WORKSPACE_DIR_NAME = "lomo-s3-sync-transfer"
