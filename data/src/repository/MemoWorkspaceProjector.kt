package com.lomo.data.repository

import com.lomo.data.engine.WorkspaceMarkdownOwner
import com.lomo.data.engine.WorkspaceMemoSummarySnapshot
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.projection.MemoProjectionProjector
import com.lomo.data.source.FileContent
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.MemoLocalDateResolver
import com.lomo.domain.model.MediaFileExtensions
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentAnalysis
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Projects storage memo entities from a single Rust workspace scan page.
 *
 * Content analysis (tags, attachments, hasTodo, hasUrl) comes from the scan summary facts that
 * the owner already projected from the same parse as Render IR — this path must not call
 * `renderMarkdown` again over memo body text.
 */
internal class MemoWorkspaceProjector(
    private val workspaceOwner: WorkspaceMarkdownOwner,
) {
    suspend fun projectShard(
        directory: MemoDirectoryType,
        metadata: FileMetadataWithId,
        existingActiveMemos: List<MemoEntity> = emptyList(),
    ): MemoProjectionChangeSet? =
        projectScannedShard(
            directory = directory,
            filename = metadata.filename,
            lastModified = metadata.lastModified,
            safUri = metadata.uriString,
            existingActiveMemos = existingActiveMemos,
        )

    suspend fun projectMainFileContent(
        file: FileContent,
        existingActiveMemos: List<MemoEntity>,
    ): MemoProjectionChangeSet.Active =
        projectScannedShard(
            directory = MemoDirectoryType.MAIN,
            filename = file.filename,
            lastModified = file.lastModified,
            safUri = null,
            existingActiveMemos = existingActiveMemos,
        ) as? MemoProjectionChangeSet.Active
            ?: throw IllegalStateException("Rust workspace scan did not publish ${file.filename}")

    private fun projectScannedShard(
        directory: MemoDirectoryType,
        filename: String,
        lastModified: Long,
        safUri: String?,
        existingActiveMemos: List<MemoEntity>,
    ): MemoProjectionChangeSet? {
        val summaries =
            workspaceOwner
                .scanWorkspace(rootPath = directory.scanRootPath())
                .filter { summary -> summary.path.substringAfterLast('/') == filename }
        if (summaries.isEmpty()) return null
        val dateKey = filename.removeSuffix(".md")
        return when (directory) {
            MemoDirectoryType.MAIN ->
                MemoProjectionChangeSet.Active(
                    memos =
                        summaries.map { summary ->
                            val memo = summary.toMemo(dateKey, lastModified)
                            val stableMemo =
                                memo
                                    .withStableRefreshId(
                                        existingMemosByTimestamp = existingActiveMemos.groupBy(MemoEntity::timestamp),
                                    ).copy(updatedAt = lastModified)
                            MemoProjectionProjector
                                .projectActive(stableMemo, summary.toContentAnalysis())
                                .entity
                        },
                    metadata =
                        LocalFileStateEntity(
                            filename = filename,
                            isTrash = false,
                            safUri = safUri,
                            lastKnownModifiedTime = lastModified,
                        ),
                    dateKey = dateKey,
                )
            MemoDirectoryType.TRASH ->
                MemoProjectionChangeSet.Trash(
                    memos =
                        summaries.map { summary ->
                            val deletedMemo =
                                summary.toMemo(dateKey, lastModified).copy(isDeleted = true, updatedAt = lastModified)
                            MemoProjectionProjector
                                .projectTrash(deletedMemo, summary.toContentAnalysis())
                                .entity
                        },
                    metadata =
                        LocalFileStateEntity(
                            filename = filename,
                            isTrash = true,
                            lastKnownModifiedTime = lastModified,
                        ),
                    dateKey = dateKey,
                )
        }
    }
}

private fun MemoDirectoryType.scanRootPath(): String? =
    when (this) {
        MemoDirectoryType.MAIN -> null
        MemoDirectoryType.TRASH -> ".trash"
    }

private fun WorkspaceMemoSummarySnapshot.toMemo(
    dateKey: String,
    fallbackTimestampMillis: Long,
): Memo =
    Memo(
        id = identity,
        timestamp = resolveScannedTimestamp(dateKey, timePart, fallbackTimestampMillis),
        content = content,
        rawContent = if (bodyStart == 0uL) content else "- $timePart $content",
        dateKey = dateKey,
        localDate = MemoLocalDateResolver.resolve(dateKey),
        tags = tags,
        imageUrls = attachments,
    )

/** Storage analysis projected from the same workspace parse that produced this scan summary. */
private fun WorkspaceMemoSummarySnapshot.toContentAnalysis(): MemoContentAnalysis {
    val audioUrls = attachments.filter(MediaFileExtensions::hasAudioExtension)
    val imageUrls = attachments.filterNot(MediaFileExtensions::hasAudioExtension)
    return MemoContentAnalysis(
        hasTodo = hasTodo,
        hasAttachment = attachments.isNotEmpty(),
        hasUrl = hasUrl,
        tags = tags,
        imageUrls = imageUrls,
        audioUrls = audioUrls,
    )
}

private fun resolveScannedTimestamp(
    dateKey: String,
    timePart: String,
    fallbackTimestampMillis: Long,
): Long {
    val zoneId = ZoneId.systemDefault()
    val localTime =
        requireNotNull(StorageTimestampFormats.parseOrNull(timePart)) {
            "Rust workspace scan published invalid time part: $timePart"
        }
    val localDate =
        StorageFilenameFormats.parseOrNull(dateKey)
            ?: Instant.ofEpochMilli(fallbackTimestampMillis).atZone(zoneId).toLocalDate()
    return LocalDateTime.of(localDate, localTime).atZone(zoneId).toInstant().toEpochMilli()
}
