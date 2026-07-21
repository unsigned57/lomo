package com.lomo.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import com.lomo.app.R
import com.lomo.app.feature.main.appendLegacyMemoGeoLocation
import com.lomo.app.presentation.sharecard.ShareCardDisplayFormatter
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.usecase.PrepareShareCardContentUseCase
import com.lomo.ui.theme.resolveLomoColorScheme
import java.time.format.DateTimeFormatter

class ShareCardBitmapRenderer(
    private val prepareShareCardContentUseCase: PrepareShareCardContentUseCase,
    private val shareCardDisplayFormatter: ShareCardDisplayFormatter,
    private val markdownWorkspaceRepository: MarkdownWorkspaceRepository,
) {
    private val shareCardTimeFormatter = DateTimeFormatter.ofPattern(SHARE_CARD_TIME_PATTERN)

    fun render(
        context: Context,
        content: String,
        title: String?,
        showTime: Boolean,
        showSignature: Boolean,
        signatureText: String,
        timestampMillis: Long?,
        tags: List<String>,
        colorSource: ColorSource,
        themeMode: ThemeMode,
        resolvedImagePaths: List<String> = emptyList(),
        geoLocation: String? = null,
        bodyTypeface: Typeface? = null,
    ): Bitmap {
        // Same body bytes as the list/card path (+ optional non-semantic geo append). Do not invent
        // Markdown link structure via pre-owner regex before renderMarkdown.
        val ownerInput = appendLegacyMemoGeoLocation(content, geoLocation)
        val document = markdownWorkspaceRepository.renderMarkdown(ownerInput)
        val totalImageSlots = countShareCardImageSlots(document)
        val hasImages = totalImageSlots > 0 || resolvedImagePaths.isNotEmpty()
        val renderInput =
            prepareRenderInput(
                context = context,
                document = document,
                title = title,
                signatureText = signatureText,
                timestampMillis = timestampMillis,
                tags = tags,
                hasImages = hasImages,
            )
        val footerContent =
            buildShareCardFooterContent(
                showTime = showTime,
                showSignature = showSignature,
                signatureText = renderInput.signatureText,
                createdAtText = renderInput.createdAtText,
            )
        val palette = resolvePalette(context, colorSource, themeMode)
        val layoutSpec = createShareCardLayoutSpec(context.resources)
        val bodyLines =
            buildMarkdownShareBodyLines(
                document = document,
                imagePlaceholder = renderInput.imagePlaceholder,
                audioPlaceholder = context.getString(R.string.share_card_placeholder_audio),
            )
        val measuredRenderInput =
            renderInput.copy(
                textLengthWithoutMarkers = shareBodyLinesTextLengthWithoutMarkers(bodyLines),
            )
        val shouldUseCenteredBody = shouldUseCenteredBody(measuredRenderInput, bodyLines)
        val paintSet =
            createShareCardPaintSet(
                resources = context.resources,
                palette = palette,
                bodyTextSizeSp = bodyTextSizeSp(measuredRenderInput.textLengthWithoutMarkers),
                shouldUseCenteredBody = shouldUseCenteredBody,
                bodyTypeface = bodyTypeface,
            )
        val loadedImages =
            loadShareImages(
                context = context,
                resolvedImagePaths = resolvedImagePaths,
                totalImageSlots = totalImageSlots.coerceAtLeast(resolvedImagePaths.size),
                targetWidth = layoutSpec.contentWidth,
            )

        return try {
            val composition =
                buildShareCardComposition(
                    displayTags = renderInput.displayTags,
                    title = renderInput.title,
                    bodyLines = bodyLines,
                    imagePlaceholder = renderInput.imagePlaceholder,
                    spec = layoutSpec,
                    paintSet = paintSet,
                    loadedImages = loadedImages,
                    footer = footerContent,
                    shouldUseCenteredBody = shouldUseCenteredBody,
                )
            renderShareCardBitmap(
                spec = layoutSpec,
                palette = palette,
                paintSet = paintSet,
                composition = composition,
                footer = footerContent,
                shouldUseCenteredBody = shouldUseCenteredBody,
            )
        } finally {
            loadedImages.values.forEach { it.recycle() }
        }
    }

    private fun prepareRenderInput(
        context: Context,
        document: MarkdownRenderDocument,
        title: String?,
        signatureText: String,
        timestampMillis: Long?,
        tags: List<String>,
        hasImages: Boolean,
    ): ShareCardRenderInput {
        val imagePlaceholder = context.getString(R.string.share_card_placeholder_image)
        // Same owner document as body IR lines — no second renderMarkdown.
        val shareCardContent =
            prepareShareCardContentUseCase(
                document = document,
                sourceTags = tags,
            )
        val createdAtText =
            formatShareCardTime(
                createdAtMillis = timestampMillis ?: System.currentTimeMillis(),
                formatter = shareCardTimeFormatter,
            )

        return ShareCardRenderInput(
            displayTags = shareCardDisplayFormatter.formatTagsForDisplay(shareCardContent.tags),
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            safeText = shareCardContent.bodyText.ifBlank { context.getString(R.string.app_name) },
            imagePlaceholder = imagePlaceholder,
            createdAtText = createdAtText,
            signatureText = signatureText,
            textLengthWithoutMarkers = shareCardContent.bodyText.length,
            hasImages = hasImages,
        )
    }

    private fun resolvePalette(
        context: Context,
        colorSource: ColorSource,
        themeMode: ThemeMode,
    ): ShareCardPalette =
        shareCardPaletteFromColorScheme(resolveLomoColorScheme(context, colorSource, themeMode))
}

internal fun buildShareCardFooterContent(
    showTime: Boolean,
    showSignature: Boolean,
    signatureText: String,
    createdAtText: String,
): ShareCardFooterContent {
    val resolvedSignatureText =
        if (showSignature) {
            signatureText.trim().ifBlank { DEFAULT_SHARE_CARD_SIGNATURE }
        } else {
            ""
        }
    val row =
        ShareCardFooterRow(
            startText = if (showTime) createdAtText else "",
            centerText = if (!showTime && resolvedSignatureText.isNotBlank()) resolvedSignatureText else "",
            endText = if (showTime) resolvedSignatureText else "",
        ).takeIf(ShareCardFooterRow::isVisible)

    return ShareCardFooterContent(
        showFooter = row != null,
        row = row,
    )
}
