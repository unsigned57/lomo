package com.lomo.data.repository

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal suspend fun deleteInboxFile(
    context: Context,
    inboxRoot: String,
    relativePath: String,
) {
    if (isContentUriRoot(inboxRoot)) {
        deleteSafInboxFile(context, inboxRoot, relativePath)
    } else {
        withContext(Dispatchers.IO) {
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
): List<InboxMarkdownFileMetadata> =
    if (isContentUriRoot(inboxRoot)) {
        listSafInboxMarkdownFiles(context, inboxRoot)
    } else {
        listDirectInboxMarkdownFiles(inboxRoot)
    }

private suspend fun listDirectInboxMarkdownFiles(inboxRoot: String): List<InboxMarkdownFileMetadata> =
    withContext(Dispatchers.IO) {
        val root = File(inboxRoot)
        val memoRoot = File(root, INBOX_MEMO_DIRECTORY)
        val rootLevelFiles =
            root.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                ?.map { file -> InboxMarkdownFileMetadata(file.name, file.lastModified()) }
                .orEmpty()
        val memoFiles =
            memoRoot.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                ?.map { file ->
                    InboxMarkdownFileMetadata(
                        relativePath = "$INBOX_MEMO_DIRECTORY/${file.name}",
                        lastModified = file.lastModified(),
                    )
                }.orEmpty()
        (rootLevelFiles + memoFiles).sortedBy { it.relativePath }.toList()
    }

private suspend fun listSafInboxMarkdownFiles(
    context: Context,
    inboxRoot: String,
): List<InboxMarkdownFileMetadata> =
    withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, inboxRoot.toUri()) ?: return@withContext emptyList()
        val rootLevelFiles =
            root.listFiles()
                .asSequence()
                .filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
                .mapNotNull { file ->
                    val name = file.name ?: return@mapNotNull null
                    InboxMarkdownFileMetadata(name, file.lastModified())
                }
        val memoFiles =
            root.findFile(INBOX_MEMO_DIRECTORY)
                ?.takeIf { it.isDirectory }
                ?.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
                ?.mapNotNull { file ->
                    val name = file.name ?: return@mapNotNull null
                    InboxMarkdownFileMetadata("$INBOX_MEMO_DIRECTORY/$name", file.lastModified())
                }.orEmpty()
        (rootLevelFiles + memoFiles).sortedBy { it.relativePath }.toList()
    }

internal suspend fun readInboxTextFile(
    context: Context,
    inboxRoot: String,
    relativePath: String,
): String? =
    if (isContentUriRoot(inboxRoot)) {
        readSafInboxFileBytes(context, inboxRoot, relativePath)?.toString(Charsets.UTF_8)
    } else {
        withContext(Dispatchers.IO) {
            val target = File(inboxRoot, relativePath)
            if (target.exists() && target.isFile) target.readText() else null
        }
    }

internal suspend fun readInboxBinaryFile(
    context: Context,
    inboxRoot: String,
    relativePath: String,
): ByteArray? =
    if (isContentUriRoot(inboxRoot)) {
        readSafInboxFileBytes(context, inboxRoot, relativePath)
    } else {
        withContext(Dispatchers.IO) {
            val target = File(inboxRoot, relativePath)
            if (target.exists() && target.isFile) target.readBytes() else null
        }
    }

internal suspend fun inboxBinaryFileExists(
    context: Context,
    inboxRoot: String,
    relativePath: String,
): Boolean =
    if (isContentUriRoot(inboxRoot)) {
        withContext(Dispatchers.IO) {
            resolveSafInboxFile(context, inboxRoot, relativePath)
                ?.isFile == true
        }
    } else {
        withContext(Dispatchers.IO) {
            val target = File(inboxRoot, relativePath)
            target.exists() && target.isFile
        }
    }

internal suspend fun copyInboxBinaryFileTo(
    context: Context,
    inboxRoot: String,
    relativePath: String,
    output: OutputStream,
): Boolean =
    if (isContentUriRoot(inboxRoot)) {
        withContext(Dispatchers.IO) {
            val target =
                resolveSafInboxFile(context, inboxRoot, relativePath)
                    ?.takeIf { it.isFile }
                    ?: return@withContext false
            context.contentResolver.openInputStream(target.uri)?.use { input ->
                input.copyTo(output)
                true
            } ?: false
        }
    } else {
        withContext(Dispatchers.IO) {
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
): ByteArray? =
    withContext(Dispatchers.IO) {
        resolveSafInboxFile(context, inboxRoot, relativePath)
            ?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            }
    }

private suspend fun deleteSafInboxFile(
    context: Context,
    inboxRoot: String,
    relativePath: String,
) {
    withContext(Dispatchers.IO) {
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
