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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.LinkedHashMap
import javax.inject.Inject

class MemoUiMapper
    internal constructor(
        private val backgroundDispatcher: CoroutineDispatcher,
    ) {
        @Inject
        constructor() : this(Dispatchers.Default)

        private val imageContentResolver = MemoUiImageContentResolver()
        private val cacheMutex = Mutex()
        private val cachedModels =
            LinkedHashMap<String, CachedMemoUiModel>(
                DEFAULT_CACHE_SIZE,
                DEFAULT_CACHE_LOAD_FACTOR,
                true,
            )

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

                val currentMemoIds = memos.asSequence().map(Memo::id).toSet()
                cacheMutex.withLock {
                    cachedModels.keys.retainAll(currentMemoIds)
                }

                val results = ArrayList<MemoUiModel>(memos.size)
                for (memo in memos) {
                    val precomputeMarkdown = memo.id in prioritizedIds
                    results +=
                        mapToCachedUiModel(
                            memo = memo,
                            rootPath = rootPath,
                            imagePath = imagePath,
                            imageMap = imageMap,
                            precomputeMarkdown = precomputeMarkdown,
                        )
                }
                results
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
            val displayContent = appendLegacyMemoGeoLocation(displayMemo.content, displayMemo.geoLocation)
            val processedContent = buildProcessedContent(displayContent, rootPath, imagePath, imageMap)
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
            val shouldShowExpand = shouldShowMemoCardExpand(displayContent)
            val collapsedSummary = buildMemoCardCollapsedSummary(displayContent, displayMemo.tags)

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

        private suspend fun mapToCachedUiModel(
            memo: Memo,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            precomputeMarkdown: Boolean,
        ): MemoUiModel {
            val displayMemo = recoverDisplayMemoIfNeeded(memo)
            val displayContent = appendLegacyMemoGeoLocation(displayMemo.content, displayMemo.geoLocation)
            val cacheKey =
                MemoUiCacheKey(
                    memo = displayMemo,
                    rootPath = rootPath,
                    imagePath = imagePath,
                    imageDependencySignature =
                        buildMemoUiImageDependencySignature(
                            content = displayContent,
                            imageMap = imageMap,
                        ),
                )
            val cached = cacheMutex.withLock { cachedModels[displayMemo.id] }
            if (cached?.key == cacheKey && (!precomputeMarkdown || cached.model.precomputedRenderPlan != null)) {
                return cached.model
            }

            val uiModel =
                mapToUiModel(
                    memo = displayMemo,
                    rootPath = rootPath,
                    imagePath = imagePath,
                    imageMap = imageMap,
                    precomputeMarkdown = precomputeMarkdown,
                    existingRenderPlan = cached?.model?.precomputedRenderPlan,
                    existingProcessedContent = cached?.model?.processedContent,
                )
            cacheMutex.withLock {
                cachedModels[displayMemo.id] =
                    CachedMemoUiModel(
                        key = cacheKey,
                        model = uiModel,
                    )
                trimCacheIfNeeded()
            }
            return uiModel
        }

        private fun trimCacheIfNeeded() {
            while (cachedModels.size > DEFAULT_CACHE_SIZE) {
                val eldestKey = cachedModels.entries.firstOrNull()?.key ?: return
                cachedModels.remove(eldestKey)
            }
        }

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
            private const val DEFAULT_CACHE_SIZE = 256
            private const val DEFAULT_CACHE_LOAD_FACTOR = 0.75f
        }
    }

private data class MemoUiCacheKey(
    val memo: Memo,
    val rootPath: String?,
    val imagePath: String?,
    val imageDependencySignature: String,
)

private data class CachedMemoUiModel(
    val key: MemoUiCacheKey,
    val model: MemoUiModel,
)
