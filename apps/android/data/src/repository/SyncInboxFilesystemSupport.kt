package com.lomo.data.repository

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.source.isContentStorageUri
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal suspend fun deleteInboxFile(
    context: Context,
    inboxRoot: String,
    relativePath: String,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) {
    if (isContentStorageUri(inboxRoot)) {
        deleteSafInboxFile(context, inboxRoot, relativePath, dispatcherProvider)
    } else {
        withContext(dispatcherProvider.io) {
            val target = File(inboxRoot, relativePath)
            if (target.exists() && !target.delete()) {
                throw IOException("Failed to delete inbox file ${target.absolutePath}")
            }
        }
    }
}

internal suspend fun listInboxMarkdownFiles(
    context: Context,
    inboxRoot: String,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
): List<InboxMarkdownFileMetadata> =
    if (isContentStorageUri(inboxRoot)) {
        listSafInboxMarkdownFiles(context, inboxRoot, dispatcherProvider)
    } else {
        listDirectInboxMarkdownFiles(inboxRoot, dispatcherProvider)
    }

private suspend fun listDirectInboxMarkdownFiles(
    inboxRoot: String,
    dispatcherProvider: DispatcherProvider,
): List<InboxMarkdownFileMetadata> =
    withContext(dispatcherProvider.io) {
        val root = File(inboxRoot)
        val memoRoot = File(root, INBOX_MEMO_DIRECTORY)
        val rootLevelFiles =
            root.listFiles()
                ?.run {
                    asSequence()
                        .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                        .map { file -> InboxMarkdownFileMetadata(file.name, file.lastModified()) }
                }
                .orEmpty()
        val memoFiles =
            memoRoot.listFiles()
                ?.run {
                    asSequence()
                        .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                        .map { file ->
                            InboxMarkdownFileMetadata(
                                relativePath = "$INBOX_MEMO_DIRECTORY/${file.name}",
                                lastModified = file.lastModified(),
                            )
                        }
                }
                .orEmpty()
        (rootLevelFiles + memoFiles).sortedBy { it.relativePath }.toList()
    }

private suspend fun listSafInboxMarkdownFiles(
    context: Context,
    inboxRoot: String,
    dispatcherProvider: DispatcherProvider,
): List<InboxMarkdownFileMetadata> =
    withContext(dispatcherProvider.io) {
        val root = DocumentFile.fromTreeUri(context, inboxRoot.toUri()) ?: return@withContext emptyList()
        val rootLevelFiles =
            root.listFiles()
                .asSequence()
                .filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
                .mapNotNull { file ->
                    val name = file.name ?: return@mapNotNull null
                    InboxMarkdownFileMetadata(name, file.lastModified())
                }
        val memoDirectory = root.findFile(INBOX_MEMO_DIRECTORY)?.takeIf { it.isDirectory }
        val memoFiles =
            memoDirectory
                ?.listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
                .mapNotNull { file ->
                    val name = file.name ?: return@mapNotNull null
                    InboxMarkdownFileMetadata("$INBOX_MEMO_DIRECTORY/$name", file.lastModified())
                }
        (rootLevelFiles + memoFiles).sortedBy { it.relativePath }.toList()
    }

internal suspend fun readInboxTextFile(
    context: Context,
    inboxRoot: String,
    relativePath: String,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
): String? =
    if (isContentStorageUri(inboxRoot)) {
        readSafInboxFileBytes(context, inboxRoot, relativePath, dispatcherProvider)?.toString(Charsets.UTF_8)
    } else {
        withContext(dispatcherProvider.io) {
            val target = File(inboxRoot, relativePath)
            if (target.exists() && target.isFile) target.readText() else null
        }
    }

internal suspend fun readInboxBinaryFile(
    context: Context,
    inboxRoot: String,
    relativePath: String,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
): ByteArray? =
    if (isContentStorageUri(inboxRoot)) {
        readSafInboxFileBytes(context, inboxRoot, relativePath, dispatcherProvider)
    } else {
        withContext(dispatcherProvider.io) {
            val target = File(inboxRoot, relativePath)
            if (target.exists() && target.isFile) {
                // behavior-contract: full-load-ok: complete payload required for parse/hash
                target.readBytes()
            } else {
                null
            }
        }
    }

internal suspend fun inboxBinaryFileExists(
    context: Context,
    inboxRoot: String,
    relativePath: String,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
): Boolean =
    if (isContentStorageUri(inboxRoot)) {
        withContext(dispatcherProvider.io) {
            resolveSafInboxFile(context, inboxRoot, relativePath)
                ?.isFile == true
        }
    } else {
        withContext(dispatcherProvider.io) {
            val target = File(inboxRoot, relativePath)
            target.exists() && target.isFile
        }
    }

internal suspend fun copyInboxBinaryFileTo(
    context: Context,
    inboxRoot: String,
    relativePath: String,
    output: OutputStream,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
): Boolean =
    if (isContentStorageUri(inboxRoot)) {
        withContext(dispatcherProvider.io) {
            val target =
                resolveSafInboxFile(context, inboxRoot, relativePath)
                    ?.takeIf { it.isFile }
                    ?: return@withContext false
            context.contentResolver.openInputStream(target.uri)?.use { input ->
                input.copyTo(output)
                true
            } == true
        }
    } else {
        withContext(dispatcherProvider.io) {
            val target = File(inboxRoot, relativePath)
            if (target.exists() && target.isFile) {
                target.inputStream().use { input -> input.copyTo(output) }
                true
            } else {
                false
            }
        }
    }

private suspend fun readSafInboxFileBytes(
    context: Context,
    inboxRoot: String,
    relativePath: String,
    dispatcherProvider: DispatcherProvider,
): ByteArray? =
    withContext(dispatcherProvider.io) {
        resolveSafInboxFile(context, inboxRoot, relativePath)
            ?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    // behavior-contract: full-load-ok: complete payload required for parse/hash
                    input.readBytes()
                }
            }
    }

private suspend fun deleteSafInboxFile(
    context: Context,
    inboxRoot: String,
    relativePath: String,
    dispatcherProvider: DispatcherProvider,
) {
    withContext(dispatcherProvider.io) {
        val target = resolveSafInboxFile(context, inboxRoot, relativePath) ?: return@withContext
        check(target.delete()) { "Failed to delete inbox SAF file $relativePath" }
    }
}

private fun resolveSafInboxFile(
    context: Context,
    inboxRoot: String,
    relativePath: String,
): DocumentFile? {
    var current = DocumentFile.fromTreeUri(context, inboxRoot.toUri()) ?: return null
    relativePath.split('/').filter(String::isNotBlank).forEach { part ->
        current = current.findFile(part) ?: return null
    }
    return current
}

internal data class InboxMarkdownFileMetadata(
    val relativePath: String,
    val lastModified: Long,
)
