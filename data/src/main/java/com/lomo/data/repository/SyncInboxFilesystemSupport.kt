package com.lomo.data.repository

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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
): List<InboxMarkdownFile> =
    if (isContentUriRoot(inboxRoot)) {
        listSafInboxMarkdownFiles(context, inboxRoot)
    } else {
        listDirectInboxMarkdownFiles(inboxRoot)
    }

private suspend fun listDirectInboxMarkdownFiles(inboxRoot: String): List<InboxMarkdownFile> =
    withContext(Dispatchers.IO) {
        val root = File(inboxRoot)
        val memoRoot = File(root, INBOX_MEMO_DIRECTORY)
        val rootLevelFiles =
            root.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                ?.map { file -> InboxMarkdownFile(file.name, file.readText(), file.lastModified()) }
                .orEmpty()
        val memoFiles =
            memoRoot.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                ?.map { file ->
                    InboxMarkdownFile(
                        relativePath = "$INBOX_MEMO_DIRECTORY/${file.name}",
                        content = file.readText(),
                        lastModified = file.lastModified(),
                    )
                }.orEmpty()
        (rootLevelFiles + memoFiles).sortedBy { it.relativePath }.toList()
    }

private suspend fun listSafInboxMarkdownFiles(
    context: Context,
    inboxRoot: String,
): List<InboxMarkdownFile> =
    withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, inboxRoot.toUri()) ?: return@withContext emptyList()
        val rootLevelFiles =
            root.listFiles()
                .asSequence()
                .filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
                .mapNotNull { file ->
                    val name = file.name ?: return@mapNotNull null
                    val content = readSafDocumentText(context, file) ?: return@mapNotNull null
                    InboxMarkdownFile(name, content, file.lastModified())
                }
        val memoFiles =
            root.findFile(INBOX_MEMO_DIRECTORY)
                ?.takeIf { it.isDirectory }
                ?.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
                ?.mapNotNull { file ->
                    val name = file.name ?: return@mapNotNull null
                    val content = readSafDocumentText(context, file) ?: return@mapNotNull null
                    InboxMarkdownFile("$INBOX_MEMO_DIRECTORY/$name", content, file.lastModified())
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

private fun readSafDocumentText(
    context: Context,
    file: DocumentFile,
): String? =
    context.contentResolver.openInputStream(file.uri)?.use { input ->
        input.readBytes().toString(Charsets.UTF_8)
    }

internal data class InboxMarkdownFile(
    val relativePath: String,
    val content: String,
    val lastModified: Long,
)
