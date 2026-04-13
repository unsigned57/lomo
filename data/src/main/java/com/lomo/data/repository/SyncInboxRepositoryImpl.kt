package com.lomo.data.repository

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.repository.WorkspaceMediaCategory.IMAGE
import com.lomo.data.repository.WorkspaceMediaCategory.VOICE
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncConflictTextMerge
import com.lomo.domain.model.SyncInboxConflictResolutionResult
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.SyncInboxRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncInboxRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val preferencesRepository: PreferencesRepository,
        private val workspaceConfigSource: WorkspaceConfigSource,
        private val markdownStorageDataSource: MarkdownStorageDataSource,
        private val workspaceMediaAccess: WorkspaceMediaAccess,
        private val memoSynchronizer: MemoSynchronizer,
    ) : SyncInboxRepository {
        override suspend fun processPendingInbox() {
            if (!preferencesRepository.isSyncInboxEnabled().first()) return

            val inboxRoot = workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX).first() ?: return
            val inboxFiles = listInboxMarkdownFiles(context, inboxRoot)
            inboxFiles.forEach { file ->
                processMarkdownFile(
                    inboxRoot = inboxRoot,
                    relativePath = file.relativePath,
                    content = file.content,
                )
            }
        }

        override suspend fun resolveConflicts(
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
        ): SyncInboxConflictResolutionResult {
            val inboxRoot =
                workspaceConfigSource
                    .getRootFlow(StorageRootType.SYNC_INBOX)
                    .first()
                    ?: return SyncInboxConflictResolutionResult.Resolved
            val remaining = mutableListOf<SyncConflictFile>()
            conflictSet.files.forEach { conflictFile ->
                val choice =
                    resolution.perFileChoices[conflictFile.relativePath]
                        ?: SyncConflictResolutionChoice.SKIP_FOR_NOW
                if (choice == SyncConflictResolutionChoice.SKIP_FOR_NOW) {
                    remaining += conflictFile
                    return@forEach
                }
                val relativePath = conflictFile.relativePath.removePrefix(INBOX_PREFIX)
                val inboxContent = readInboxTextFile(context, inboxRoot, relativePath) ?: return@forEach
                val targetContent =
                    when (choice) {
                        SyncConflictResolutionChoice.KEEP_LOCAL -> conflictFile.localContent
                        SyncConflictResolutionChoice.KEEP_REMOTE -> conflictFile.remoteContent
                        SyncConflictResolutionChoice.MERGE_TEXT ->
                            SyncConflictTextMerge.merge(conflictFile.localContent, conflictFile.remoteContent)
                        SyncConflictResolutionChoice.SKIP_FOR_NOW -> null
                    } ?: run {
                        remaining += conflictFile
                        return@forEach
                    }
                commitImportedFile(inboxRoot, relativePath, inboxContent, targetContent)
            }
            return if (remaining.isEmpty()) {
                SyncInboxConflictResolutionResult.Resolved
            } else {
                SyncInboxConflictResolutionResult.Pending(
                    conflictSet.copy(files = remaining),
                )
            }
        }

        private suspend fun processMarkdownFile(
            inboxRoot: String,
            relativePath: String,
            content: String,
        ) {
            val imported = importMediaReferences(inboxRoot, relativePath, content)
            val targetFilename = relativePath.substringAfterLast('/')
            val localContent = markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, targetFilename)
            val resolvedContent =
                when {
                    localContent == null -> imported.rewrittenMarkdown
                    localContent == imported.rewrittenMarkdown -> imported.rewrittenMarkdown
                    else -> SyncConflictTextMerge.merge(localContent, imported.rewrittenMarkdown)
                }
            if (resolvedContent == null) {
                throw com.lomo.domain.usecase.SyncConflictException(
                    SyncConflictSet(
                        source = SyncBackendType.INBOX,
                        files =
                            listOf(
                                SyncConflictFile(
                                    relativePath = INBOX_PREFIX + relativePath,
                                    localContent = localContent,
                                    remoteContent = imported.rewrittenMarkdown,
                                    isBinary = false,
                                ),
                            ),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }
            commitImportedFile(inboxRoot, relativePath, content, resolvedContent)
        }

        private suspend fun commitImportedFile(
            inboxRoot: String,
            relativePath: String,
            originalMarkdown: String,
            targetContent: String,
        ) {
            val imported = importMediaReferences(inboxRoot, relativePath, originalMarkdown)
            val finalContent = if (targetContent == imported.rewrittenMarkdown) targetContent else targetContent
            val targetFilename = relativePath.substringAfterLast('/')
            markdownStorageDataSource.saveFileIn(
                directory = MemoDirectoryType.MAIN,
                filename = targetFilename,
                content = finalContent,
                append = false,
            )
            memoSynchronizer.refreshImportedSync(targetFilename)
            deleteInboxFile(inboxRoot, relativePath)
            imported.importedAttachments.forEach { attachment ->
                deleteInboxFile(inboxRoot, attachment)
            }
        }

        private suspend fun importMediaReferences(
            inboxRoot: String,
            relativePath: String,
            markdown: String,
        ): ImportedInboxContent {
            var rewritten = markdown
            val importedAttachments = mutableListOf<String>()
            extractLocalAttachmentPaths(markdown).forEach { rawPath ->
                val normalized = normalizeAttachmentPath(rawPath) ?: return@forEach
                val category =
                    when {
                        normalized.startsWith("images/") -> IMAGE
                        normalized.startsWith("voice/") -> VOICE
                        else -> return@forEach
                    }
                val sourceBytes = readInboxBinaryFile(context, inboxRoot, normalized) ?: return@forEach
                val destinationFilename = stableImportedFilename(relativePath, normalized)
                workspaceMediaAccess.writeFile(category, destinationFilename, sourceBytes)
                rewritten = rewritten.replace(rawPath, destinationFilename)
                importedAttachments += normalized
            }
            return ImportedInboxContent(
                rewrittenMarkdown = rewritten,
                importedAttachments = importedAttachments.distinct(),
            )
        }

        private suspend fun deleteInboxFile(
            inboxRoot: String,
            relativePath: String,
        ) {
            if (isContentUriRoot(inboxRoot)) {
                deleteSafFile(context, inboxRoot, relativePath)
            } else {
                withContext(Dispatchers.IO) {
                    val target = File(inboxRoot, relativePath)
                    if (target.exists() && !target.delete()) {
                        throw IOException("Failed to delete inbox file ${target.absolutePath}")
                    }
                }
            }
        }

        private fun extractLocalAttachmentPaths(content: String): List<String> {
            val markdownImages = IMAGE_PATTERN.findAll(content).mapNotNull { it.groupValues.getOrNull(1) }
            val wikiImages = WIKI_IMAGE_PATTERN.findAll(content).mapNotNull { it.groupValues.getOrNull(1) }
            val audioLinks = AUDIO_PATTERN.findAll(content).mapNotNull { it.groupValues.getOrNull(1) }
            return (markdownImages + wikiImages + audioLinks)
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("http://") && !it.startsWith("https://") }
                .distinct()
                .toList()
        }

        private fun normalizeAttachmentPath(path: String): String? =
            path
                .replace('\\', '/')
                .removePrefix("./")
                .takeIf { normalized ->
                    normalized.startsWith("images/") || normalized.startsWith("voice/")
                }

        private fun stableImportedFilename(
            markdownRelativePath: String,
            attachmentRelativePath: String,
        ): String {
            val baseName = attachmentRelativePath.substringAfterLast('/').substringBeforeLast('.')
            val extension = attachmentRelativePath.substringAfterLast('.', "")
            val hashInput = "$markdownRelativePath::$attachmentRelativePath"
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest(hashInput.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
                    .take(IMPORTED_FILENAME_HASH_LENGTH)
            return if (extension.isBlank()) "${baseName}_$digest" else "${baseName}_$digest.$extension"
        }

        private companion object {
            const val INBOX_PREFIX = "inbox/"
            const val IMPORTED_FILENAME_HASH_LENGTH = 10
            val IMAGE_PATTERN = Regex("""!\[.*?]\((.*?)\)""")
            val WIKI_IMAGE_PATTERN = Regex("""!\[\[(.*?)]]""")
            val AUDIO_PATTERN =
                Regex("""(?<!!)\[[^\]]*]\((.+?\.(?:m4a|mp3|ogg|wav|aac))\)""", RegexOption.IGNORE_CASE)
        }
    }

private suspend fun listInboxMarkdownFiles(
    context: Context,
    inboxRoot: String,
): List<InboxMarkdownFile> =
    if (isContentUriRoot(inboxRoot)) {
        listSafMarkdownFiles(context, inboxRoot)
    } else {
        listDirectMarkdownFiles(inboxRoot)
    }

private suspend fun listDirectMarkdownFiles(inboxRoot: String): List<InboxMarkdownFile> =
    withContext(Dispatchers.IO) {
        val root = File(inboxRoot)
        root.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.map { InboxMarkdownFile(relativePath = it.name, content = it.readText()) }
            ?.toList()
            ?: emptyList()
    }

private suspend fun listSafMarkdownFiles(
    context: Context,
    inboxRoot: String,
): List<InboxMarkdownFile> =
    withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, inboxRoot.toUri()) ?: return@withContext emptyList()
        root.listFiles()
            .asSequence()
            .filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
            .sortedBy { it.name.orEmpty() }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                val content =
                    context.contentResolver.openInputStream(file.uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: return@mapNotNull null
                InboxMarkdownFile(relativePath = name, content = content)
            }.toList()
    }

private suspend fun readInboxTextFile(
    context: Context,
    inboxRoot: String,
    relativePath: String,
): String? =
    if (isContentUriRoot(inboxRoot)) {
        readSafFileBytes(context, inboxRoot, relativePath)?.toString(Charsets.UTF_8)
    } else {
        withContext(Dispatchers.IO) {
            val target = File(inboxRoot, relativePath)
            if (target.exists() && target.isFile) target.readText() else null
        }
    }

private suspend fun readInboxBinaryFile(
    context: Context,
    inboxRoot: String,
    relativePath: String,
): ByteArray? =
    if (isContentUriRoot(inboxRoot)) {
        readSafFileBytes(context, inboxRoot, relativePath)
    } else {
        withContext(Dispatchers.IO) {
            val target = File(inboxRoot, relativePath)
            if (target.exists() && target.isFile) target.readBytes() else null
        }
    }

private suspend fun readSafFileBytes(
    context: Context,
    inboxRoot: String,
    relativePath: String,
): ByteArray? =
    withContext(Dispatchers.IO) {
        resolveSafFile(context, inboxRoot, relativePath)
            ?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            }
    }

private suspend fun deleteSafFile(
    context: Context,
    inboxRoot: String,
    relativePath: String,
) {
    withContext(Dispatchers.IO) {
        val target = resolveSafFile(context, inboxRoot, relativePath) ?: return@withContext
        if (!target.delete()) {
            throw IOException("Failed to delete inbox SAF file $relativePath")
        }
    }
}

private fun resolveSafFile(
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

private data class InboxMarkdownFile(
    val relativePath: String,
    val content: String,
)

private data class ImportedInboxContent(
    val rewrittenMarkdown: String,
    val importedAttachments: List<String>,
)
