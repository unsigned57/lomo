package com.lomo.app.util

import android.graphics.Bitmap
import androidx.compose.ui.text.font.FontFamily
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.ContrastLevel
import com.lomo.domain.model.MediaClass
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.ui.theme.TypographyScales
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val SHARE_CARD_TIME_PATTERN = "yyyy-MM-dd HH:mm"
internal const val MAX_SHARE_BITMAP_HEIGHT_PX = 4096
internal const val MIN_SHARE_CARD_CANVAS_WIDTH_PX = 720
internal const val SHARE_CARD_CANVAS_WIDTH_RATIO = 0.9f
internal const val SHORT_BODY_CENTER_THRESHOLD = 42
internal const val SHORT_BODY_MAX_NON_BLANK_LINES = 3

internal const val IMAGE_SCALE_SKIP_MIN = 0.95f
internal const val IMAGE_SCALE_SKIP_MAX = 1.05f
internal const val IMAGE_SAMPLE_WIDTH_DIVISOR = 2
internal const val MIN_RENDER_DIMENSION_PX = 1

internal const val CONTENT_URI_PREFIX = "content://"
internal const val FILE_URI_PREFIX = "file://"
internal const val HTTP_PREFIX = "http://"
internal const val HTTPS_PREFIX = "https://"
internal const val DEFAULT_SHARE_CARD_SIGNATURE = "Lomo"

/** Presentation and media options for one share-card bitmap. */
data class ShareCardRenderOptions(
    val showTime: Boolean,
    val showSignature: Boolean,
    val signatureText: String,
    val createdAtText: String = "",
    val timestampMillis: Long? = null,
    val tags: List<String> = emptyList(),
    val colorSource: ColorSource = ColorSource.default(),
    val contrastLevel: ContrastLevel = ContrastLevel.default(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pureBlackDark: Boolean = false,
    val fontFamily: FontFamily = FontFamily.SansSerif,
    val typographyScales: TypographyScales = TypographyScales(),
    val resolvedImagePaths: List<String> = emptyList(),
)

internal data class ShareCardFooterContent(val showFooter: Boolean, val row: ShareCardFooterRow? = null)

internal data class ShareCardFooterRow(
    val startText: String = "",
    val centerText: String = "",
    val endText: String = "",
) {
    val isVisible: Boolean
        get() = startText.isNotBlank() || centerText.isNotBlank() || endText.isNotBlank()
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

internal fun resolveShareCardCreatedAtText(
    createdAtText: String,
    timestampMillis: Long?,
    formatter: DateTimeFormatter,
): String = createdAtText.trim().ifEmpty {
    formatShareCardTime(
        createdAtMillis = timestampMillis ?: System.currentTimeMillis(),
        formatter = formatter,
    )
}

internal fun formatShareCardTime(createdAtMillis: Long, formatter: DateTimeFormatter): String = Instant
    .ofEpochMilli(createdAtMillis)
    .atZone(ZoneId.systemDefault())
    .format(formatter)

internal fun collectShareCardImageDestinations(document: MarkdownRenderDocument): List<String> = document.attachments
    .asSequence()
    .filter { it.mediaClass != MediaClass.AUDIO }
    .map { it.destination }
    .toList()

internal fun shouldUseCenteredShareCardBody(
    document: MarkdownRenderDocument,
    bodyText: String,
    hasImages: Boolean,
): Boolean {
    if (hasImages || bodyText.isBlank()) return false
    if (bodyText.length > SHORT_BODY_CENTER_THRESHOLD) return false
    if (document.blocks.any { it !is MarkdownRenderBlock.Paragraph }) return false
    val nonBlankLines = bodyText.lineSequence().count { it.isNotBlank() }
    return nonBlankLines <= SHORT_BODY_MAX_NON_BLANK_LINES
}

internal data class ShareCardUiState(
    val document: MarkdownRenderDocument,
    val displayTags: List<String>,
    val title: String?,
    val isBlankFallback: Boolean,
    val emptyFallbackText: String,
    val centeredBody: Boolean,
    val imagesByDestination: Map<String, Bitmap>,
    val imagePlaceholder: String,
    val audioPlaceholder: String,
    val footer: ShareCardFooterContent,
)
