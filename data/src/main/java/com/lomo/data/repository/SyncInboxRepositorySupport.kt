package com.lomo.data.repository

import android.content.Context
import com.lomo.data.repository.WorkspaceMediaCategory.IMAGE
import com.lomo.data.repository.WorkspaceMediaCategory.VOICE
import com.lomo.domain.model.MediaFileExtensions
import com.lomo.domain.model.SyncConflictAutoResolutionAdvisor
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolutionChoice
import java.security.MessageDigest
import java.io.OutputStream

internal const val INBOX_MEMO_DIRECTORY = "memo"
private const val INBOX_IMAGE_DIRECTORY = "images"
private const val INBOX_VOICE_DIRECTORY = "voice"
private const val INBOX_RECORDING_DIRECTORY = "recording"
private const val IMPORTED_FILENAME_HASH_LENGTH = 10
private val IMAGE_PATTERN = Regex("""!\[.*?]\((.*?)\)""")
private val WIKI_IMAGE_PATTERN = Regex("""!\[\[(.*?)]]""")
private val AUDIO_PATTERN =
    Regex(
        """(?<!!)\[[^\]]*]\((.+?\.(?:${MediaFileExtensions.AUDIO.joinToString("|")}))\)""",
        RegexOption.IGNORE_CASE,
    )

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
    markdown: String,
): ImportedInboxContent {
    var rewritten = markdown
    val attachmentReferences = extractInboxAttachmentReferences(markdown)
    attachmentReferences.forEach { attachment ->
        val destinationFilename = stableImportedInboxFilename(attachment.rewriteKey)
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
    markdown: String,
): InboxMediaImportResult {
    val preview = previewInboxMediaReferences(markdown = markdown)
    val resolvedAttachments = mutableListOf<Pair<InboxAttachmentReference, ResolvedInboxAttachmentSource>>()
    val missingAttachments = mutableListOf<String>()
    extractInboxAttachmentReferences(markdown).forEach { attachment ->
        val resolvedAttachment =
            resolveInboxAttachmentSource(
                context = context,
                inboxRoot = inboxRoot,
                attachment = attachment,
            )
        if (resolvedAttachment == null) {
            missingAttachments += attachment.sourcePath
            return@forEach
        }
        resolvedAttachments += attachment to resolvedAttachment
    }
    if (missingAttachments.isNotEmpty()) {
        return InboxMediaImportResult.MissingAttachments(
            preview = preview,
            missingAttachments = missingAttachments.distinct(),
        )
    }
    resolvedAttachments.forEach { (attachment, resolvedAttachment) ->
        val destinationFilename = stableImportedInboxFilename(attachment.rewriteKey)
        workspaceMediaAccess.writeFileFromStream(
            category = attachment.category,
            filename = destinationFilename,
            source = resolvedAttachment.copyTo,
        )
    }
    return InboxMediaImportResult.Success(
        preview =
            preview.copy(
                importedAttachments =
                    resolvedAttachments
                        .map { (_, resolvedAttachment) -> resolvedAttachment.relativePath }
                        .distinct(),
            ),
    )
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

private suspend fun resolveInboxAttachmentSource(
    context: Context,
    inboxRoot: String,
    attachment: InboxAttachmentReference,
): ResolvedInboxAttachmentSource? {
    attachment.sourceCandidates.forEach { candidate ->
        val exists =
            inboxBinaryFileExists(
                context = context,
                inboxRoot = inboxRoot,
                relativePath = candidate,
            )
        if (!exists) {
            return@forEach
        }
        return ResolvedInboxAttachmentSource(
            relativePath = candidate,
            copyTo = { output ->
                check(
                    copyInboxBinaryFileTo(
                        context = context,
                        inboxRoot = inboxRoot,
                        relativePath = candidate,
                        output = output,
                    ),
                ) {
                    "Cannot open inbox attachment stream: $candidate"
                }
            },
        )
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
    if (MediaFileExtensions.hasAudioExtension(sourcePath)) {
        VOICE
    } else {
        IMAGE
    }

private fun stableImportedInboxFilename(attachmentRelativePath: String): String {
    val baseName = attachmentRelativePath.substringAfterLast('/').substringBeforeLast('.')
    val extension = attachmentRelativePath.substringAfterLast('.', "")
    val digest =
        MessageDigest.getInstance("SHA-256")
            .digest(attachmentRelativePath.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(IMPORTED_FILENAME_HASH_LENGTH)
    return if (extension.isBlank()) "${baseName}_$digest" else "${baseName}_$digest.$extension"
}

internal sealed interface InboxMediaImportResult {
    data class Success(
        val preview: ImportedInboxContent,
    ) : InboxMediaImportResult

    data class MissingAttachments(
        val preview: ImportedInboxContent,
        val missingAttachments: List<String>,
    ) : InboxMediaImportResult
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

private data class ResolvedInboxAttachmentSource(
    val relativePath: String,
    val copyTo: suspend (OutputStream) -> Unit,
)
