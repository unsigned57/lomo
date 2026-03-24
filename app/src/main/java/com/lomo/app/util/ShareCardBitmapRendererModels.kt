package com.lomo.app.util

import android.graphics.Bitmap
import android.text.StaticLayout
import android.text.TextPaint

internal const val SHARE_CARD_TIME_PATTERN = "yyyy-MM-dd HH:mm"
internal const val MAX_SHARE_BITMAP_HEIGHT_PX = 4096
internal const val MAX_SHARE_BODY_LINES = 60
internal const val SHORT_BODY_CENTER_THRESHOLD = 42
internal const val SHORT_BODY_MAX_NON_BLANK_LINES = 3
internal const val REMOTE_IMAGE_TIMEOUT_MS = 10_000

internal const val MIN_SHARE_CARD_CANVAS_WIDTH_PX = 720
internal const val SHARE_CARD_CANVAS_WIDTH_RATIO = 0.9f
internal const val OUTER_PADDING_DP = 24f
internal const val CARD_PADDING_DP = 30f
internal const val CARD_CORNER_DP = 24f
internal const val LINE_SPACING_DP = 7f
internal const val TAG_BOTTOM_SPACING_DP = 16f
internal const val TITLE_BOTTOM_SPACING_DP = 12f
internal const val CODE_HORIZONTAL_PADDING_DP = 12f
internal const val CODE_VERTICAL_PADDING_DP = 10f
internal const val CODE_CORNER_DP = 10f
internal const val IMAGE_CORNER_DP = 12f
internal const val IMAGE_VERTICAL_PADDING_DP = 4f
internal const val MAX_IMAGE_HEIGHT_DP = 360f
internal const val MIN_CARD_HEIGHT_DP = 330f
internal const val FOOTER_DIVIDER_TOP_SPACING_DP = 20f
internal const val FOOTER_DIVIDER_STROKE_DP = 1f
internal const val FOOTER_ROW_TOP_SPACING_DP = 14f

internal const val TAG_TEXT_SIZE_SP = 11.5f
internal const val TITLE_TEXT_SIZE_SP = 13.5f
internal const val FOOTER_TEXT_SIZE_SP = 12f
internal const val BODY_TEXT_SIZE_SHORT_SP = 22f
internal const val BODY_TEXT_SIZE_MEDIUM_SP = 20f
internal const val BODY_TEXT_SIZE_LONG_SP = 17f
internal const val BODY_TEXT_SIZE_DEFAULT_SP = 15.5f
internal const val BODY_TEXT_SIZE_SHORT_THRESHOLD = 32
internal const val BODY_TEXT_SIZE_MEDIUM_THRESHOLD = 88
internal const val BODY_TEXT_SIZE_LONG_THRESHOLD = 180

internal const val TAG_LETTER_SPACING = 0.03f
internal const val TITLE_LETTER_SPACING = 0.01f
internal const val CENTERED_BODY_LETTER_SPACING = 0.01f
internal const val DEFAULT_BODY_LETTER_SPACING = 0.015f
internal const val EMPHASIZED_LETTER_SPACING = 0.01f
internal const val FOOTER_LETTER_SPACING = 0.02f
internal const val BULLET_TEXT_SCALE = 0.94f
internal const val QUOTE_TEXT_SCALE = 0.92f
internal const val CODE_TEXT_SCALE = 0.84f
internal const val LAYOUT_LINE_SPACING_MULTIPLIER = 1.3f

internal const val CODE_BACKGROUND_ALPHA = 66
internal const val IMAGE_SCALE_SKIP_MIN = 0.95f
internal const val IMAGE_SCALE_SKIP_MAX = 1.05f
internal const val IMAGE_SAMPLE_WIDTH_DIVISOR = 2
internal const val MAX_TAG_LINES = 2
internal const val MAX_TITLE_LINES = 1
internal const val MIN_RENDER_DIMENSION_PX = 1
internal const val NO_IMAGE_INDEX = -1
internal const val IMAGE_MARKER_INDEX_GROUP = 1
internal const val MD_IMAGE_PATH_GROUP_INDEX = 2

internal const val BLANK_LAYOUT_TEXT = " "
internal const val TAG_JOIN_SEPARATOR = "  \u00B7  "
internal const val CONTENT_URI_PREFIX = "content://"
internal const val FILE_URI_PREFIX = "file://"
internal const val HTTP_PREFIX = "http://"
internal const val HTTPS_PREFIX = "https://"
internal const val CODE_BLOCK_PREFIX = "    "
internal const val QUOTE_PREFIX = "│ "
internal const val BULLET_PREFIX = "• "
internal const val UNCHECKED_TODO_PREFIX = "☐"
internal const val CHECKED_TODO_PREFIX = "☑"

internal const val IMAGE_MARKER_PREFIX = "\uFFFCIMG"
internal const val IMAGE_MARKER_SUFFIX = "\uFFFC"
internal val IMAGE_MARKER_PATTERN = Regex("""\uFFFCIMG(\d+)\uFFFC""")
internal val WIKI_IMAGE_REGEX = Regex("""!\[\[(.*?)\]\]""")
internal val MD_IMAGE_REGEX = Regex("""!\[(.*?)\]\((.*?)\)""")
internal val AUDIO_EXTENSIONS = setOf(".m4a", ".mp3", ".aac", ".wav")

internal data class PreprocessedShareCardContent(
    val contentForProcessing: String,
    val totalImageSlots: Int,
    val hasImages: Boolean,
)

internal data class ShareCardRenderInput(
    val displayTags: List<String>,
    val title: String?,
    val safeText: String,
    val imagePlaceholder: String,
    val createdAtText: String,
    val activeDayCountText: String,
    val textLengthWithoutMarkers: Int,
    val hasImages: Boolean,
)

internal data class ShareCardFooterContent(
    val showFooter: Boolean,
    val showTime: Boolean,
    val createdAtText: String,
    val activeDayCountText: String,
)

internal data class ShareCardLayoutSpec(
    val canvasWidth: Int,
    val outerPadding: Float,
    val cardPadding: Float,
    val cardCorner: Float,
    val lineSpacing: Float,
    val tagBottomSpacing: Float,
    val titleBottomSpacing: Float,
    val codeHorizontalPadding: Float,
    val codeVerticalPadding: Float,
    val codeCorner: Float,
    val imageCorner: Float,
    val imageVerticalPadding: Float,
    val maxImageHeightPx: Float,
    val dividerTopSpacing: Float,
    val dividerStrokeWidth: Float,
    val footerRowTopSpacing: Float,
    val minCardHeight: Float,
    val contentWidth: Int,
)

internal data class ShareCardPaintSet(
    val tagPaint: TextPaint,
    val titlePaint: TextPaint,
    val paragraphPaint: TextPaint,
    val bulletPaint: TextPaint,
    val quotePaint: TextPaint,
    val codePaint: TextPaint,
    val footerPaint: TextPaint,
)

internal data class ShareCardComposition(
    val tagLayout: StaticLayout?,
    val titleLayout: StaticLayout?,
    val bodyRenderLines: List<ShareCardRenderLine>,
    val bodyContentHeight: Float,
    val footerBlockHeight: Float,
    val bitmapHeight: Int,
)

internal data class ShareCardRenderLine(
    val type: ShareBodyLineType,
    val layout: StaticLayout,
    val height: Float,
    val imageBitmap: Bitmap? = null,
    val imageDrawWidth: Float = 0f,
    val imageDrawHeight: Float = 0f,
)

internal enum class ShareBodyLineType {
    Paragraph,
    Bullet,
    Quote,
    Code,
    Image,
    Blank,
}

internal data class ShareBodyLine(
    val text: String,
    val type: ShareBodyLineType,
    val imageIndex: Int = NO_IMAGE_INDEX,
)

internal data class ShareCardPalette(
    val bgStart: Int,
    val bgEnd: Int,
    val card: Int,
    val cardBorder: Int,
    val bodyText: Int,
    val secondaryText: Int,
    val tagBg: Int,
    val tagText: Int,
    val divider: Int,
)
