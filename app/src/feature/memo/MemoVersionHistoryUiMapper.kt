package com.lomo.app.feature.memo

import android.net.Uri
import com.lomo.app.feature.main.MemoUiImageContentResolver
import com.lomo.app.feature.main.buildMemoUiImageDependencySignature
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.repository.MarkdownWorkspaceRepository

internal data class MemoVersionHistoryUiModel(
    val revision: MemoRevision,
    val processedContent: String,
    val renderDocument: MarkdownRenderDocument,
)

internal class MemoVersionHistoryUiMapper(
    private val markdownWorkspaceRepository: MarkdownWorkspaceRepository,
    private val imageContentResolver: MemoUiImageContentResolver = MemoUiImageContentResolver(),
) {
    private val cachedModels = linkedMapOf<String, CachedMemoVersionHistoryUiModel>()

    fun mapToUiModels(
        revisions: List<MemoRevision>,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): List<MemoVersionHistoryUiModel> {
        val revisionIds = revisions.map(MemoRevision::revisionId).toSet()
        cachedModels.keys.retainAll(revisionIds)

        return revisions.map { revision ->
            val displayContent = revision.memoContent.ifBlank { revision.summary }
            val cacheKey =
                MemoVersionHistoryUiCacheKey(
                    revision = revision,
                    rootPath = rootPath,
                    imagePath = imagePath,
                    imageDependencySignature =
                        buildMemoUiImageDependencySignature(
                            imageMap = imageMap,
                        ),
                )
            val cachedModel = cachedModels[revision.revisionId]
            if (cachedModel?.key == cacheKey) {
                cachedModel.model
            } else {
                val processedContent = displayContent
                val renderDocument =
                    imageContentResolver.resolveRenderDocumentImages(
                        document = markdownWorkspaceRepository.renderMarkdown(displayContent),
                        rootPath = rootPath,
                        imagePath = imagePath,
                        imageMap = imageMap,
                    )
                MemoVersionHistoryUiModel(
                    revision = revision,
                    processedContent = processedContent,
                    renderDocument = renderDocument,
                ).also { model ->
                    cachedModels[revision.revisionId] =
                        CachedMemoVersionHistoryUiModel(
                            key = cacheKey,
                            model = model,
                        )
                }
            }
        }
    }
}

private data class CachedMemoVersionHistoryUiModel(
    val key: MemoVersionHistoryUiCacheKey,
    val model: MemoVersionHistoryUiModel,
)

private data class MemoVersionHistoryUiCacheKey(
    val revision: MemoRevision,
    val rootPath: String?,
    val imagePath: String?,
    val imageDependencySignature: String,
)
