package com.lomo.app.feature.memo

import android.net.Uri
import com.lomo.app.feature.main.MemoUiImageContentResolver
import com.lomo.app.feature.main.buildImageMapDependencySignatureForPaths
import com.lomo.domain.model.MediaClass
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.markdown.MarkdownRenderContractException
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.repository.MarkdownWorkspaceRepository

internal data class MemoVersionHistoryUiModel(
    val revision: MemoRevision,
    val processedContent: String,
    val preview: MemoVersionPreview,
)

/**
 * A revision body is user data and may exceed the owner's presentation budget. That body cannot be
 * previewed, but the revision stays listed and restorable, so one oversized version never takes
 * the whole history sheet down.
 */
internal sealed interface MemoVersionPreview {
    data class Rendered(val document: MarkdownRenderDocument) : MemoVersionPreview

    data class Unavailable(val code: String) : MemoVersionPreview
}

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
            // behavior-contract: loop-io-ok: history page is bounded; each body renders alone
            val sourcePreview =
                try {
                    MemoVersionPreview.Rendered(markdownWorkspaceRepository.renderMarkdown(displayContent))
                } catch (failure: MarkdownRenderContractException) {
                    MemoVersionPreview.Unavailable(failure.code)
                }
            val imagePaths =
                when (sourcePreview) {
                    is MemoVersionPreview.Rendered ->
                        sourcePreview.document.attachments
                            .filter { attachment -> attachment.mediaClass != MediaClass.AUDIO }
                            .map { attachment -> attachment.destination }
                            .toSet()

                    is MemoVersionPreview.Unavailable -> emptySet()
                }
            val cacheKey =
                MemoVersionHistoryUiCacheKey(
                    revision = revision,
                    rootPath = rootPath,
                    imagePath = imagePath,
                    imageDependencySignature =
                    buildImageMapDependencySignatureForPaths(
                        imagePaths = imagePaths,
                        imageMap = imageMap,
                    ),
                )
            val cachedModel = cachedModels[revision.revisionId]
            if (cachedModel?.key == cacheKey) {
                cachedModel.model
            } else {
                val processedContent = displayContent
                val preview =
                    when (sourcePreview) {
                        is MemoVersionPreview.Rendered ->
                            MemoVersionPreview.Rendered(
                                imageContentResolver.resolveRenderDocumentImages(
                                    document = sourcePreview.document,
                                    rootPath = rootPath,
                                    imagePath = imagePath,
                                    imageMap = imageMap,
                                ),
                            )

                        is MemoVersionPreview.Unavailable -> sourcePreview
                    }
                MemoVersionHistoryUiModel(
                    revision = revision,
                    processedContent = processedContent,
                    preview = preview,
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
