package com.lomo.app.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.ui.graphics.toArgb
import com.lomo.app.R
import com.lomo.app.presentation.sharecard.ShareCardDisplayFormatter
import com.lomo.domain.model.ShareCardTextInput
import com.lomo.domain.usecase.PrepareShareCardContentUseCase
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareCardBitmapRenderer
    @Inject
    constructor(
        private val prepareShareCardContentUseCase: PrepareShareCardContentUseCase,
        private val shareCardDisplayFormatter: ShareCardDisplayFormatter,
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
            resolvedImagePaths: List<String> = emptyList(),
        ): Bitmap {
            val preprocessed = preprocessShareCardContent(content, resolvedImagePaths.isNotEmpty())
            val renderInput =
                prepareRenderInput(
                    context = context,
                    processedContent = preprocessed.contentForProcessing,
                    title = title,
                    signatureText = signatureText,
                    timestampMillis = timestampMillis,
                    tags = tags,
                    hasImages = preprocessed.hasImages,
                )
            val footerContent =
                buildShareCardFooterContent(
                    showTime = showTime,
                    showSignature = showSignature,
                    signatureText = renderInput.signatureText,
                    createdAtText = renderInput.createdAtText,
                )
            val palette = resolvePalette(context)
            val layoutSpec = createShareCardLayoutSpec(context.resources)
            val bodyLines = buildShareBodyLines(renderInput.safeText, renderInput.imagePlaceholder)
            val shouldUseCenteredBody = shouldUseCenteredBody(renderInput, bodyLines)
            val paintSet =
                createShareCardPaintSet(
                    resources = context.resources,
                    palette = palette,
                    bodyTextSizeSp = bodyTextSizeSp(renderInput.textLengthWithoutMarkers),
                    shouldUseCenteredBody = shouldUseCenteredBody,
                )
            val loadedImages =
                loadShareImages(
                    context = context,
                    resolvedImagePaths = resolvedImagePaths,
                    totalImageSlots = preprocessed.totalImageSlots,
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
            processedContent: String,
            title: String?,
            signatureText: String,
            timestampMillis: Long?,
            tags: List<String>,
            hasImages: Boolean,
        ): ShareCardRenderInput {
            val imagePlaceholder = context.getString(R.string.share_card_placeholder_image)
            val shareCardContent =
                prepareShareCardContentUseCase(
                    ShareCardTextInput(
                        content = processedContent,
                        sourceTags = tags,
                    ),
                )
            val safeText =
                shareCardDisplayFormatter
                    .formatBodyText(
                        bodyText = shareCardContent.bodyText,
                        audioPlaceholder = context.getString(R.string.share_card_placeholder_audio),
                        imagePlaceholder = imagePlaceholder,
                        imageNamedPlaceholderPattern =
                            context.getString(R.string.share_card_placeholder_image_named),
                    ).ifBlank {
                        context.getString(R.string.app_name)
                    }
            val createdAtText =
                formatShareCardTime(
                    createdAtMillis = timestampMillis ?: System.currentTimeMillis(),
                    formatter = shareCardTimeFormatter,
                )

            return ShareCardRenderInput(
                displayTags = shareCardDisplayFormatter.formatTagsForDisplay(shareCardContent.tags),
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                safeText = safeText,
                imagePlaceholder = imagePlaceholder,
                createdAtText = createdAtText,
                signatureText = signatureText,
                textLengthWithoutMarkers =
                    if (hasImages) {
                        IMAGE_MARKER_PATTERN.replace(safeText, "").length
                    } else {
                        safeText.length
                    },
                hasImages = hasImages,
            )
        }

        private fun resolvePalette(context: Context): ShareCardPalette =
            buildShareCardColorScheme(context).toShareCardPalette()

        private fun Context.isDarkTheme(): Boolean =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        @OptIn(ExperimentalMaterial3ExpressiveApi::class)
        private fun buildShareCardColorScheme(context: Context): ColorScheme {
            val darkTheme = context.isDarkTheme()
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
                darkTheme -> darkColorScheme()
                else -> expressiveLightColorScheme()
            }
        }

        private fun ColorScheme.toShareCardPalette(): ShareCardPalette =
            ShareCardPalette(
                bgStart = surfaceContainerLowest.toArgb(),
                bgEnd = surface.toArgb(),
                card = surfaceContainerLow.toArgb(),
                cardBorder = outlineVariant.toArgb(),
                bodyText = onSurface.toArgb(),
                secondaryText = onSurfaceVariant.toArgb(),
                tagBg = secondaryContainer.toArgb(),
                tagText = onSecondaryContainer.toArgb(),
                divider = outlineVariant.toArgb(),
            )
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
