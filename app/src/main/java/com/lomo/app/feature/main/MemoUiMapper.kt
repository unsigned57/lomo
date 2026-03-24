package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.domain.model.Memo
import com.lomo.ui.component.card.buildMemoCardCollapsedSummary
import com.lomo.ui.component.card.shouldShowMemoCardExpand
import com.lomo.ui.component.markdown.ImmutableNode
import com.lomo.ui.component.markdown.MarkdownKnownTagFilter
import com.lomo.ui.component.markdown.MarkdownParser
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MemoUiMapper
    @Inject
    constructor() {
        private val imageContentResolver = MemoUiImageContentResolver()

        suspend fun mapToUiModels(
            memos: List<Memo>,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            prioritizedMemoIds: Set<String> = emptySet(),
        ): List<MemoUiModel> =
            withContext(Dispatchers.Default) {
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
            existingNode: ImmutableNode? = null,
            existingProcessedContent: String? = null,
        ): MemoUiModel {
            val processedContent = buildProcessedContent(memo.content, rootPath, imagePath, imageMap)
            val canReuseExistingNode =
                existingNode != null &&
                    existingProcessedContent != null &&
                    existingProcessedContent == processedContent
            val parsedNode =
                when {
                    canReuseExistingNode -> existingNode
                    precomputeMarkdown ->
                        MarkdownKnownTagFilter.eraseKnownTags(
                            MarkdownParser.parse(processedContent),
                            memo.tags,
                        )

                    else -> null
                }
            val imageUrls = imageContentResolver.extractImageUrls(processedContent)
            val shouldShowExpand = shouldShowMemoCardExpand(memo.content)
            val collapsedSummary = buildMemoCardCollapsedSummary(memo.content, memo.tags)

            return MemoUiModel(
                memo = memo,
                processedContent = processedContent,
                markdownNode = parsedNode,
                tags = memo.tags.toImmutableList(),
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

        private companion object {
            private const val DEFAULT_PRIORITY_WINDOW_SIZE = 20
        }
    }
