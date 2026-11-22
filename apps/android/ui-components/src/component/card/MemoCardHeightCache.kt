package com.lomo.ui.component.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.ui.component.markdown.MarkdownIrPresentationPlan
import com.lomo.ui.util.SynchronizedLruStore

internal data class MemoCardHeightCacheKey(
    val rawContentHash: Int,
    val processedContentHash: Int,
    val processedContentLength: Int,
    val sourceByteLength: ULong,
    val presentationPlanHash: Int,
    val densityBits: Int,
    val fontScaleBits: Int,
)

/**
 * Bounded process-local cache of measured memo card body heights (px).
 *
 * Returning cards seed their initial measured height immediately on re-entry instead of resetting
 * to zero and triggering a visible expand-footer / card-height jump after navigation or todo edits.
 */
internal object MemoCardHeightCache {
    private const val MAX_ENTRIES = 256
    private val store = SynchronizedLruStore<MemoCardHeightCacheKey, Int>(MAX_ENTRIES)

    fun keyFor(
        processedContent: String,
        renderDocument: MarkdownRenderDocument,
        presentationPlan: MarkdownIrPresentationPlan?,
        density: Float,
        fontScale: Float,
        content: String = processedContent,
    ): MemoCardHeightCacheKey = MemoCardHeightCacheKey(
        rawContentHash = content.hashCode(),
        processedContentHash = processedContent.hashCode(),
        processedContentLength = processedContent.length,
        sourceByteLength = renderDocument.sourceByteLength,
        presentationPlanHash = presentationPlan?.hashCode() ?: 0,
        densityBits = density.toBits(),
        fontScaleBits = fontScale.toBits(),
    )

    fun get(key: MemoCardHeightCacheKey): Int? = store.get(key)

    fun put(key: MemoCardHeightCacheKey, heightPx: Int) {
        if (heightPx > 0) {
            store.put(key, heightPx)
        }
    }

    fun clear() {
        store.clear()
    }
}

internal class MemoCardMeasuredHeight(val heightPx: Int, val onBodyHeightChanged: (Int) -> Unit)

@Composable
internal fun rememberMemoCardMeasuredHeight(
    content: String,
    timestamp: Long,
    processedContent: String,
    renderDocument: MarkdownRenderDocument,
    presentationPlan: MarkdownIrPresentationPlan?,
    density: Density,
): MemoCardMeasuredHeight {
    val heightCacheKey =
        remember(
            content,
            processedContent,
            renderDocument,
            presentationPlan,
            density.density,
            density.fontScale,
        ) {
            MemoCardHeightCache.keyFor(
                processedContent = processedContent,
                renderDocument = renderDocument,
                presentationPlan = presentationPlan,
                density = density.density,
                fontScale = density.fontScale,
                content = content,
            )
        }
    val cachedHeightPx = MemoCardHeightCache.get(heightCacheKey)
    var bodyHeightPx by
        remember(timestamp, density.density, density.fontScale) {
            mutableIntStateOf(cachedHeightPx ?: 0)
        }
    val onBodyHeightChanged =
        remember(heightCacheKey) {
            { measuredHeightPx: Int ->
                if (measuredHeightPx > 0) {
                    MemoCardHeightCache.put(heightCacheKey, measuredHeightPx)
                }
                if (bodyHeightPx != measuredHeightPx) {
                    bodyHeightPx = measuredHeightPx
                }
            }
        }
    return MemoCardMeasuredHeight(
        heightPx = cachedHeightPx ?: bodyHeightPx,
        onBodyHeightChanged = onBodyHeightChanged,
    )
}
