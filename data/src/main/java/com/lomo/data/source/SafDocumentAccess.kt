package com.lomo.data.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.util.runNonFatalCatching
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

internal class SafDocumentAccess(
    private val context: Context,
    private val rootUri: Uri,
    private val subDir: String? = null,
) {
    private var cachedTrashDir: DocumentFile? = null

    fun root(): DocumentFile? =
        try {
            val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
            if (subDir != null) {
                root.findFile(subDir) ?: root.createDirectory(subDir)
            } else {
                root
            }
        } catch (error: SecurityException) {
            Timber.w(error, "Lost SAF permission for root uri: %s", rootUri)
            null
        }

    fun trashDir(): DocumentFile? = resolveTrashDir(createIfMissing = false)

    fun orCreateTrashDir(): DocumentFile? = resolveTrashDir(createIfMissing = true)

    fun <T> withSecurityRetry(
        operation: String,
        fallbackValue: T,
        block: () -> T,
    ): T {
        repeat(SECURITY_RETRY_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: SecurityException) {
                invalidateCaches()
                val hasRetry = attempt + 1 < SECURITY_RETRY_ATTEMPTS
                if (hasRetry) {
                    Timber.w(error, "%s hit SecurityException; retrying once", operation)
                } else {
                    Timber.e(error, "%s hit SecurityException; giving up", operation)
                }
            }
        }
        return fallbackValue
    }

    fun <T> withSecurityRetryOrThrow(
        operation: String,
        block: () -> T,
    ): T {
        var lastException: SecurityException? = null
        repeat(SECURITY_RETRY_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: SecurityException) {
                lastException = error
                invalidateCaches()
                val hasRetry = attempt + 1 < SECURITY_RETRY_ATTEMPTS
                if (hasRetry) {
                    Timber.w(error, "%s hit SecurityException; retrying once", operation)
                } else {
                    Timber.e(error, "%s hit SecurityException; giving up", operation)
                }
            }
        }
        throw lastException ?: SecurityException("$operation failed with lost SAF permission")
    }

    fun readTextFromUri(uri: Uri): String? =
        try {
            context.contentResolver.openInputStream(uri)?.use {
                BufferedReader(InputStreamReader(it)).readText()
            }
        } catch (_: Exception) {
            null
        }

    fun writeTextToUri(
        uri: Uri,
        mode: String,
        content: String,
    ) {
        val output =
            context.contentResolver.openOutputStream(uri, mode)
                ?: throw IOException("openOutputStream returned null for uri=$uri mode=$mode")
        output.use { stream ->
            stream.write(content.toByteArray())
        }
    }

    fun overwriteWithRollback(
        uri: Uri,
        content: String,
    ) {
        val backup = readTextFromUri(uri)
        runNonFatalCatching {
            writeTextToUri(uri, "wt", content)
        }.getOrElse { error ->
            if (backup != null) {
                runCatching { writeTextToUri(uri, "wt", backup) }
                    .onFailure { rollbackError ->
                        Timber.e(rollbackError, "Rollback failed after overwrite error for uri=%s", uri)
                    }
            }
            throw error
        }
    }

    private fun resolveTrashDir(createIfMissing: Boolean): DocumentFile? {
        cachedTrashDir?.let { cached ->
            if (cached.isUsableSafDocument()) {
                return cached
            }
            cachedTrashDir = null
        }
        val root = root()
        val resolved =
            root
                ?.let { currentRoot ->
                    if (createIfMissing) {
                        currentRoot.findFile(SAF_TRASH_DIR_NAME) ?: currentRoot.createDirectory(SAF_TRASH_DIR_NAME)
                    } else {
                        currentRoot.findFile(SAF_TRASH_DIR_NAME)
                    }
                }?.takeIf { it.isUsableSafDocument() }
        cachedTrashDir = resolved
        return resolved
    }

    private fun invalidateCaches() {
        cachedTrashDir = null
    }

    private fun DocumentFile.isUsableSafDocument(): Boolean =
        try {
            exists() && canRead() && (isFile || isDirectory)
        } catch (_: SecurityException) {
            false
        }

    private companion object {
        const val SECURITY_RETRY_ATTEMPTS = 2
    }
}
