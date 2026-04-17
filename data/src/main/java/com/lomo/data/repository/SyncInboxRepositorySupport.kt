package com.lomo.data.repository

import android.content.Context
import com.lomo.data.repository.WorkspaceMediaCategory.IMAGE
import com.lomo.data.repository.WorkspaceMediaCategory.VOICE
import com.lomo.domain.model.SyncConflictAutoResolutionAdvisor
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolutionChoice
import java.security.MessageDigest

internal const val INBOX_MEMO_DIRECTORY = "memo"
private const val INBOX_IMAGE_DIRECTORY = "images"
private const val INBOX_VOICE_DIRECTORY = "voice"
private const val INBOX_RECORDING_DIRECTORY = "recording"
private const val IMPORTED_FILENAME_HASH_LENGTH = 10
private val SYNC_INBOX_AUDIO_EXTENSIONS = setOf("m4a", "mp3", "ogg", "wav", "aac")
private val IMAGE_PATTERN = Regex("""!\[.*?]\((.*?)\)""")
private val WIKI_IMAGE_PATTERN = Regex("""!\[\[(.*?)]]""")
private val AUDIO_PATTERN =
    Regex("""(?<!!)\[[^\]]*]\((.+?\.(?:m4a|mp3|ogg|wav|aac))\)""", RegexOption.IGNORE_CASE)

internal fun safeAutoResolvedContent(conflictFile: SyncConflictFile): String? =
    when (SyncConflictAutoResolutionAdvisor.safeAutoResolutionChoice(conflictFile)) {
        SyncConflictResolutionChoice.KEEP_LOCAL -> conflictFile.localContent
        SyncConflictResolutionChoice.KEEP_REMOTE -> conflictFile.remoteContent
        SyncConflictResolutionChoice.MERGE_TEXT -> SyncConflictAutoResolutionAdvisor.mergedText(conflictFile)
        SyncConflictResolutionChoice.SKIP_FOR_NOW,
        null,
        -> null
    }

internal fun previewInboxMediaReferences(
    relativePath: String,
    markdown: String,
): ImportedInboxContent {
    var rewritten = markdown
    val attachmentReferences = extractInboxAttachmentReferences(markdown)
    attachmentReferences.forEach { attachment ->
        val destinationFilename = stableImportedInboxFilename(relativePath, attachment.rewriteKey)
        rewritten =
            rewritten.replace(
                attachment.referenceText,
                attachment.rewrittenReferenceText(destinationFilename),
            )
    }
    return ImportedInboxContent(
        rewrittenMarkdown = rewritten,
        importedAttachments = attachmentReferences.map { it.sourceCandidates.first() }.distinct(),
    )
}

internal suspend fun importInboxMediaReferences(
    context: Context,
    workspaceMediaAccess: WorkspaceMediaAccess,
    inboxRoot: String,
    relativePath: String,
    markdown: String,
): ImportedInboxContent {
    val preview = previewInboxMediaReferences(relativePath = relativePath, markdown = markdown)
    val importedAttachments = mutableListOf<String>()
    extractInboxAttachmentReferences(markdown).forEach { attachment ->
        val resolvedAttachment =
            resolveInboxAttachment(
                context = context,
                inboxRoot = inboxRoot,
                attachment = attachment,
            ) ?: error("Referenced sync inbox attachment not found: ${attachment.sourcePath}")
        val destinationFilename = stableImportedInboxFilename(relativePath, attachment.rewriteKey)
        workspaceMediaAccess.writeFile(attachment.category, destinationFilename, resolvedAttachment.bytes)
        importedAttachments += resolvedAttachment.relativePath
    }
    return preview
        .copy(importedAttachments = importedAttachments.distinct())
}

private fun extractInboxAttachmentReferences(content: String): List<InboxAttachmentReference> {
    val markdownImages =
        IMAGE_PATTERN.findAll(content).mapNotNull { match ->
            match.groupValues.getOrNull(1)?.let { reference ->
                inboxAttachmentReference(
                    referenceText = reference,
                    sourcePath = reference,
                    category = inboxAttachmentCategory(reference),
                )
            }
        }
    val wikiImages =
        WIKI_IMAGE_PATTERN.findAll(content).mapNotNull { match ->
            match.groupValues.getOrNull(1)?.let { reference ->
                inboxAttachmentReference(
                    referenceText = reference,
                    sourcePath = reference.substringBefore('|'),
                    category = inboxAttachmentCategory(reference.substringBefore('|')),
                )
            }
        }
    val audioLinks =
        AUDIO_PATTERN.findAll(content).mapNotNull { match ->
            match.groupValues.getOrNull(1)?.let { reference ->
                inboxAttachmentReference(referenceText = reference, sourcePath = reference, category = VOICE)
            }
        }
    return (markdownImages + wikiImages + audioLinks)
        .distinctBy { it.referenceText to it.category }
        .toList()
}

private fun inboxAttachmentReference(
    referenceText: String,
    sourcePath: String,
    category: WorkspaceMediaCategory,
): InboxAttachmentReference? {
    val normalized = normalizeInboxAttachmentPath(sourcePath) ?: return null
    val sourceCandidates =
        when {
            normalized.startsWith("$INBOX_IMAGE_DIRECTORY/") ||
                normalized.startsWith("$INBOX_VOICE_DIRECTORY/") ||
                normalized.startsWith("$INBOX_RECORDING_DIRECTORY/") -> listOf(normalized)
            normalized.contains('/') -> listOf(normalized)
            category == IMAGE -> listOf("$INBOX_IMAGE_DIRECTORY/$normalized", normalized)
            else ->
                listOf(
                    "$INBOX_VOICE_DIRECTORY/$normalized",
                    "$INBOX_RECORDING_DIRECTORY/$normalized",
                    normalized,
                )
        }
    return InboxAttachmentReference(
        category = category,
        referenceText = referenceText,
        sourcePath = normalized,
        rewriteKey = normalized,
        sourceCandidates = sourceCandidates.distinct(),
    )
}

private suspend fun resolveInboxAttachment(
    context: Context,
    inboxRoot: String,
    attachment: InboxAttachmentReference,
): ResolvedInboxAttachment? {
    attachment.sourceCandidates.forEach { candidate ->
        val bytes = readInboxBinaryFile(context, inboxRoot, candidate) ?: return@forEach
        return ResolvedInboxAttachment(relativePath = candidate, bytes = bytes)
    }
    return null
}

private fun normalizeInboxAttachmentPath(path: String): String? =
    path
        .trim()
        .replace('\\', '/')
        .removePrefix("./")
        .takeUnless { normalized ->
            normalized.isEmpty() ||
                normalized.startsWith("http://", ignoreCase = true) ||
                normalized.startsWith("https://", ignoreCase = true)
        }

private fun inboxAttachmentCategory(sourcePath: String): WorkspaceMediaCategory =
    if (sourcePath.substringAfterLast('.', "").lowercase() in SYNC_INBOX_AUDIO_EXTENSIONS) {
        VOICE
    } else {
        IMAGE
    }

private fun stableImportedInboxFilename(
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

internal data class ImportedInboxContent(
    val rewrittenMarkdown: String,
    val importedAttachments: List<String>,
)

private data class InboxAttachmentReference(
    val category: WorkspaceMediaCategory,
    val referenceText: String,
    val sourcePath: String,
    val rewriteKey: String,
    val sourceCandidates: List<String>,
) {
    fun rewrittenReferenceText(destinationFilename: String): String =
        if (referenceText.contains('|')) {
            "$destinationFilename|${referenceText.substringAfter('|')}"
        } else {
            destinationFilename
        }
}

private data class ResolvedInboxAttachment(
    val relativePath: String,
    val bytes: ByteArray,
)
