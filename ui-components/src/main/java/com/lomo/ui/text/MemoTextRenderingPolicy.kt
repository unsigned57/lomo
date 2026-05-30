package com.lomo.ui.text

internal enum class MemoTextRenderer {
    PlatformText,
    CustomCanvasText,
}

internal enum class MemoTextRenderingCapability {
    Selection,
    TapHandling,
    Links,
    InlineStyles,
    SearchHighlights,
}

internal enum class MemoTextLayoutCacheField {
    Text,
    MaxWidthPx,
    MaxLines,
    EllipsizeLastVisibleLine,
    LineHeightPx,
    BaselinePx,
    BaseLetterSpacingPx,
    ProtectedRanges,
}

internal data class MemoTextProtectedRangeKey(
    val start: Int,
    val end: Int,
)

internal data class MemoTextLayoutCacheKey(
    val text: String,
    val maxWidthPx: Float,
    val maxLines: Int,
    val ellipsizeLastVisibleLine: Boolean,
    val lineHeightPx: Float,
    val baselinePx: Float,
    val baseLetterSpacingPx: Float,
    val protectedRanges: List<MemoTextProtectedRangeKey>,
)

internal fun MemoTextLayoutInput.toMemoTextLayoutCacheKey(): MemoTextLayoutCacheKey =
    MemoTextLayoutCacheKey(
        text = text,
        maxWidthPx = maxWidthPx,
        maxLines = maxLines,
        ellipsizeLastVisibleLine = ellipsizeLastVisibleLine,
        lineHeightPx = lineHeightPx,
        baselinePx = baselinePx,
        baseLetterSpacingPx = baseLetterSpacingPx,
        protectedRanges =
            protectedRanges.map { range ->
                MemoTextProtectedRangeKey(
                    start = range.start,
                    end = range.end,
                )
            },
    )

internal data class MemoTextRenderingRequest(
    val text: String,
    val selectionRequired: Boolean = false,
    val tapHandlingRequired: Boolean = false,
    val hasLinks: Boolean = false,
    val hasInlineStyles: Boolean = false,
    val hasSearchHighlights: Boolean = false,
)

internal data class MemoTextRenderingDecision(
    val renderer: MemoTextRenderer,
    val customEngineCapabilities: Set<MemoTextRenderingCapability>,
    val customLayoutCacheFields: Set<MemoTextLayoutCacheField>,
)

internal fun resolveMemoTextRenderingPolicy(request: MemoTextRenderingRequest): MemoTextRenderingDecision {
    val capabilities = request.customEngineCapabilities()
    return if (capabilities.isEmpty()) {
        MemoTextRenderingDecision(
            renderer = MemoTextRenderer.PlatformText,
            customEngineCapabilities = emptySet(),
            customLayoutCacheFields = emptySet(),
        )
    } else {
        MemoTextRenderingDecision(
            renderer = MemoTextRenderer.CustomCanvasText,
            customEngineCapabilities = capabilities,
            customLayoutCacheFields = CUSTOM_TEXT_LAYOUT_CACHE_FIELDS,
        )
    }
}

private fun MemoTextRenderingRequest.customEngineCapabilities(): Set<MemoTextRenderingCapability> =
    buildSet {
        if (selectionRequired) add(MemoTextRenderingCapability.Selection)
        if (tapHandlingRequired) add(MemoTextRenderingCapability.TapHandling)
        if (hasLinks) add(MemoTextRenderingCapability.Links)
        if (hasInlineStyles) add(MemoTextRenderingCapability.InlineStyles)
        if (hasSearchHighlights) add(MemoTextRenderingCapability.SearchHighlights)
    }

private val CUSTOM_TEXT_LAYOUT_CACHE_FIELDS =
    setOf(
        MemoTextLayoutCacheField.Text,
        MemoTextLayoutCacheField.MaxWidthPx,
        MemoTextLayoutCacheField.MaxLines,
        MemoTextLayoutCacheField.EllipsizeLastVisibleLine,
        MemoTextLayoutCacheField.LineHeightPx,
        MemoTextLayoutCacheField.BaselinePx,
        MemoTextLayoutCacheField.BaseLetterSpacingPx,
        MemoTextLayoutCacheField.ProtectedRanges,
    )
