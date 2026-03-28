package com.lomo.app.feature.memo

import android.net.Uri
import com.lomo.app.feature.main.MemoUiImageContentResolver
import com.lomo.domain.model.MemoRevision

internal data class MemoVersionHistoryUiModel(
    val revision: MemoRevision,
    val processedContent: String,
)

internal class MemoVersionHistoryUiMapper(
    private val imageContentResolver: MemoUiImageContentResolver = MemoUiImageContentResolver(),
) {
    fun mapToUiModels(
        revisions: List<MemoRevision>,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): List<MemoVersionHistoryUiModel> =
        revisions.map { revision ->
            val displayContent = revision.memoContent.ifBlank { revision.summary }
            MemoVersionHistoryUiModel(
                revision = revision,
                processedContent =
                    imageContentResolver.buildProcessedContent(
                        content = displayContent,
                        rootPath = rootPath,
                        imagePath = imagePath,
                        imageMap = imageMap,
                    ),
            )
        }
}
