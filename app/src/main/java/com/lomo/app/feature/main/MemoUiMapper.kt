package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.ui.component.card.buildMemoCardCollapsedSummary
import com.lomo.ui.component.card.shouldShowMemoCardExpand
import com.lomo.ui.component.markdown.ModernMarkdownRenderPlan
import com.lomo.ui.component.markdown.createModernMarkdownRenderPlan
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

class MemoUiMapper
    internal constructor(
        private val backgroundDispatcher: CoroutineDispatcher,
    ) {
        @Inject
        constructor() : this(Dispatchers.Default)

        private val imageContentResolver = MemoUiImageContentResolver()

        suspend fun mapToUiModels(
            memos: List<Memo>,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            prioritizedMemoIds: Set<String> = emptySet(),
        ): List<MemoUiModel> =
            withContext(backgroundDispatcher) {
                if (memos.isEmpty()) {
                    return@withContext emptyList()
                }

                val prioritizedIds =
                    if (prioritizedMemoIds.isNotEmpty()) {
                        prioritizedMemoIds
                    } else {
                        memos
                            .asSequence()
                            .take(DEFAULT_PRIORITY_WINDOW_SIZE)
                            .map { it.id }
                            .toSet()
                    }

                memos.map { memo ->
                    mapToUiModel(
                        memo = memo,
                        rootPath = rootPath,
                        imagePath = imagePath,
                        imageMap = imageMap,
                        precomputeMarkdown = memo.id in prioritizedIds,
                    )
                }
            }

        fun mapToUiModel(
            memo: Memo,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            precomputeMarkdown: Boolean = true,
            existingRenderPlan: ModernMarkdownRenderPlan? = null,
            existingProcessedContent: String? = null,
        ): MemoUiModel {
            val displayMemo = recoverDisplayMemoIfNeeded(memo)
            val processedContent = buildProcessedContent(displayMemo.content, rootPath, imagePath, imageMap)
            val canReuseExistingRenderPlan =
                existingRenderPlan != null &&
                    existingProcessedContent != null &&
                    existingProcessedContent == processedContent
            val renderPlan =
                when {
                    canReuseExistingRenderPlan -> existingRenderPlan
                    precomputeMarkdown ->
                        createModernMarkdownRenderPlan(
                            content = processedContent,
                            knownTagsToStrip = displayMemo.tags,
                        )

                    else -> null
                }
            val imageUrls = imageContentResolver.extractImageUrls(processedContent)
            val shouldShowExpand = shouldShowMemoCardExpand(displayMemo.content)
            val collapsedSummary = buildMemoCardCollapsedSummary(displayMemo.content, displayMemo.tags)

            return MemoUiModel(
                memo = displayMemo,
                processedContent = processedContent,
                precomputedRenderPlan = renderPlan,
                tags = displayMemo.tags.toImmutableList(),
                imageUrls = imageUrls,
                shouldShowExpand = shouldShowExpand,
                collapsedSummary = collapsedSummary,
            )
        }

        private fun buildProcessedContent(
            content: String,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
        ): String =
            imageContentResolver.buildProcessedContent(
                content = content,
                rootPath = rootPath,
                imagePath = imagePath,
                imageMap = imageMap,
            )

        private fun recoverDisplayMemoIfNeeded(memo: Memo): Memo {
            val normalizedRawContent = memo.rawContent.trim()
            val lines = memo.rawContent.lines()
            val header = lines.firstOrNull()?.let(StorageTimestampFormats::parseMemoHeaderLine)
            val parsedTime = header?.timePart?.let(StorageTimestampFormats::parseOrNull)
            return if (header == null || parsedTime == null) {
                if (normalizedRawContent.isNotBlank() && normalizedRawContent != memo.content) {
                    memo.copy(content = normalizedRawContent)
                } else {
                    memo
                }
            } else {
                val recoveredContent = buildRecoveredContent(lines, header.contentPart)
                val resolvedContent = resolveRecoveredContent(memo.content, recoveredContent)
                val shouldRecoverContent = memo.content != resolvedContent
                val shouldRecoverTimestamp =
                    !storedTimestampMatchesRecoveredHeader(
                        storedTimestamp = memo.timestamp,
                        expectedDate = StorageFilenameFormats.parseOrNull(memo.dateKey),
                        expectedTime = parsedTime,
                        rawTimePart = header.timePart,
                    )

                if (!shouldRecoverContent && !shouldRecoverTimestamp) {
                    memo
                } else {
                    val recoveredTimestamp =
                        if (shouldRecoverTimestamp) {
                            resolveRecoveredTimestamp(memo.dateKey, parsedTime, memo.timestamp)
                        } else {
                            memo.timestamp
                        }

                    memo.copy(
                        timestamp = recoveredTimestamp,
                        updatedAt =
                            if (shouldRecoverTimestamp && memo.updatedAt == memo.timestamp) {
                                recoveredTimestamp
                            } else {
                                memo.updatedAt
                            },
                        content = if (shouldRecoverContent) resolvedContent else memo.content,
                    )
                }
            }
        }

        private fun resolveRecoveredContent(
            storedContent: String,
            recoveredContent: String,
        ): String =
            if (recoveredContent.isBlank() && storedContent.isNotBlank()) {
                storedContent
            } else {
                recoveredContent
            }

        private fun buildRecoveredContent(
            lines: List<String>,
            headerContent: String,
        ): String =
            buildString {
                append(headerContent)
                for (index in 1 until lines.size) {
                    if (isEmpty()) {
                        append(lines[index])
                    } else {
                        append("\n").append(lines[index])
                    }
                }
            }.trim()

        private fun resolveRecoveredTimestamp(
            dateKey: String,
            parsedTime: LocalTime,
            fallbackTimestamp: Long,
        ): Long =
            StorageFilenameFormats.parseOrNull(dateKey)
                ?.let { parsedDate ->
                    LocalDateTime
                        .of(parsedDate, parsedTime)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                } ?: fallbackTimestamp

        private fun storedTimestampMatchesRecoveredHeader(
            storedTimestamp: Long,
            expectedDate: LocalDate?,
            expectedTime: LocalTime,
            rawTimePart: String,
        ): Boolean {
            val storedDateTime =
                storedTimestamp
                    .takeIf { it > 0L }
                    ?.let { timestamp ->
                        Instant
                            .ofEpochMilli(timestamp)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDateTime()
                    }
                    ?: return false
            val matchesDate = expectedDate == null || storedDateTime.toLocalDate() == expectedDate

            return matchesDate &&
                if (rawTimePart.count { it == ':' } >= 2) {
                    storedDateTime.hour == expectedTime.hour &&
                        storedDateTime.minute == expectedTime.minute &&
                        storedDateTime.second == expectedTime.second
                } else {
                    storedDateTime.hour == expectedTime.hour &&
                        storedDateTime.minute == expectedTime.minute
                }
        }

        private companion object {
            private const val DEFAULT_PRIORITY_WINDOW_SIZE = 20
        }
    }
