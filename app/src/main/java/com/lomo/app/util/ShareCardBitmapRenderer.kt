package com.lomo.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.lomo.app.R
import com.lomo.app.presentation.sharecard.ShareCardDisplayFormatter
import com.lomo.domain.model.ShareCardTextInput
import com.lomo.domain.usecase.PrepareShareCardContentUseCase
import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

@Singleton
class ShareCardBitmapRenderer
    @Inject
    constructor(
        private val prepareShareCardContentUseCase: PrepareShareCardContentUseCase,
        private val shareCardDisplayFormatter: ShareCardDisplayFormatter,
    ) {
        private val shareCardTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun render(
            context: Context,
            content: String,
            title: String?,
            style: String,
            showTime: Boolean,
            timestampMillis: Long?,
            tags: List<String>,
            activeDayCount: Int?,
        ): Bitmap {
            val resources = context.resources
            val shareCardContent =
                prepareShareCardContentUseCase(
                    ShareCardTextInput(
                        content = content,
                        sourceTags = tags,
                    ),
                )
            val displayTags = shareCardDisplayFormatter.formatTagsForDisplay(shareCardContent.tags)
            val safeText =
                shareCardDisplayFormatter.formatBodyText(
                    bodyText = shareCardContent.bodyText,
                    audioPlaceholder = context.getString(R.string.share_card_placeholder_audio),
                    imagePlaceholder = context.getString(R.string.share_card_placeholder_image),
                    imageNamedPlaceholderPattern = context.getString(R.string.share_card_placeholder_image_named),
                ).ifBlank {
                    context.getString(R.string.app_name)
                }
            val palette = resolvePalette(style)
            val createdAtMillis = timestampMillis ?: System.currentTimeMillis()
            val createdAtText = formatShareCardTime(createdAtMillis)
            val activeDayCountText =
                activeDayCount
                    ?.takeIf { it > 0 }
                    ?.let { dayCount ->
                        context.resources.getQuantityString(R.plurals.share_card_recorded_days, dayCount, dayCount)
                    }.orEmpty()
            val showFooter = showTime || activeDayCountText.isNotBlank()
            val bodyTextSizeSp =
                when {
                    safeText.length <= 32 -> 27f
                    safeText.length <= 88 -> 22f
                    safeText.length <= 180 -> 18f
                    else -> 16f
                }

            val canvasWidth = (resources.displayMetrics.widthPixels.coerceAtLeast(720) * 0.9f).roundToInt()
            val outerPadding = dp(resources, 24f)
            val cardPadding = dp(resources, 26f)
            val cardCorner = dp(resources, 28f)
            val lineSpacing = dp(resources, 6f)
            val codeHorizontalPadding = dp(resources, 10f)
            val codeVerticalPadding = dp(resources, 8f)
            val minCardHeight = dp(resources, 330f)
            val contentWidth =
                (canvasWidth - outerPadding * 2 - cardPadding * 2)
                    .roundToInt()
                    .coerceAtLeast(1)

            val tagPaint =
                createTextPaint(
                    color = palette.tagText,
                    textSizePx = sp(resources, 12f),
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
                )
            val titlePaint =
                createTextPaint(
                    color = palette.secondaryText,
                    textSizePx = sp(resources, 14f),
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
                )
            val paragraphPaint =
                createTextPaint(
                    color = palette.bodyText,
                    textSizePx = sp(resources, bodyTextSizeSp),
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
                )
            val bulletPaint =
                createTextPaint(
                    color = palette.bodyText,
                    textSizePx = sp(resources, bodyTextSizeSp * 0.94f),
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
                )
            val quotePaint =
                createTextPaint(
                    color = palette.secondaryText,
                    textSizePx = sp(resources, bodyTextSizeSp * 0.9f),
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
                )
            val codePaint =
                createTextPaint(
                    color = palette.bodyText,
                    textSizePx = sp(resources, bodyTextSizeSp * 0.84f),
                    typeface = Typeface.MONOSPACE,
                )
            val footerPaint =
                createTextPaint(
                    color = palette.secondaryText,
                    textSizePx = sp(resources, 13f),
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
                )

            val tagLayout =
                displayTags
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = "   ") { "#$it" }
                    ?.let { buildStaticLayout(it, tagPaint, contentWidth, maxLines = 2) }
            val titleLayout =
                title
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { buildStaticLayout(it, titlePaint, contentWidth, maxLines = 1) }
            val bodyRenderLines =
                buildShareBodyLines(safeText).map { line ->
                    when (line.type) {
                        ShareBodyLineType.Blank -> {
                            RenderLine(
                                type = line.type,
                                layout = buildStaticLayout(" ", paragraphPaint, contentWidth),
                                height = lineSpacing,
                            )
                        }

                        ShareBodyLineType.Code -> {
                            val codeWidth = max(1, contentWidth - (codeHorizontalPadding * 2).roundToInt())
                            val layout = buildStaticLayout(line.text, codePaint, codeWidth)
                            RenderLine(
                                type = line.type,
                                layout = layout,
                                height = layout.height + codeVerticalPadding * 2,
                            )
                        }

                        ShareBodyLineType.Quote -> {
                            val layout = buildStaticLayout(line.text, quotePaint, contentWidth)
                            RenderLine(type = line.type, layout = layout, height = layout.height.toFloat())
                        }

                        ShareBodyLineType.Bullet -> {
                            val layout = buildStaticLayout(line.text, bulletPaint, contentWidth)
                            RenderLine(type = line.type, layout = layout, height = layout.height.toFloat())
                        }

                        ShareBodyLineType.Paragraph -> {
                            val layout = buildStaticLayout(line.text, paragraphPaint, contentWidth)
                            RenderLine(type = line.type, layout = layout, height = layout.height.toFloat())
                        }
                    }
                }

            var contentHeight = 0f
            if (tagLayout != null) {
                contentHeight += tagLayout.height + dp(resources, 14f)
            }
            if (titleLayout != null) {
                contentHeight += titleLayout.height + dp(resources, 10f)
            }
            bodyRenderLines.forEachIndexed { index, line ->
                contentHeight += line.height
                if (index != bodyRenderLines.lastIndex) {
                    contentHeight += lineSpacing
                }
            }

            val footerTextHeight = footerPaint.fontMetrics.let { it.descent - it.ascent }
            val footerBlockHeight =
                if (showFooter) {
                    dp(resources, 18f) + dp(resources, 1f) + dp(resources, 12f) + footerTextHeight
                } else {
                    0f
                }

            val maxCardHeight = MAX_SHARE_BITMAP_HEIGHT_PX - outerPadding * 2
            val cardHeight =
                max(minCardHeight, contentHeight + footerBlockHeight + cardPadding * 2)
                    .coerceAtMost(maxCardHeight)
            val bitmapHeight =
                (outerPadding * 2 + cardHeight)
                    .roundToInt()
                    .coerceIn(1, MAX_SHARE_BITMAP_HEIGHT_PX)
            val bitmap = Bitmap.createBitmap(canvasWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val backgroundPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader =
                        LinearGradient(
                            0f,
                            0f,
                            canvasWidth.toFloat(),
                            bitmapHeight.toFloat(),
                            palette.bgStart,
                            palette.bgEnd,
                            Shader.TileMode.CLAMP,
                        )
                }
            canvas.drawRect(0f, 0f, canvasWidth.toFloat(), bitmapHeight.toFloat(), backgroundPaint)

            val cardRect =
                RectF(
                    outerPadding,
                    outerPadding,
                    canvasWidth - outerPadding,
                    bitmapHeight - outerPadding,
                )
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.card }
            canvas.drawRoundRect(cardRect, cardCorner, cardCorner, cardPaint)
            val borderPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.cardBorder
                    strokeWidth = dp(resources, 1f)
                    this.style = Paint.Style.STROKE
                }
            canvas.drawRoundRect(cardRect, cardCorner, cardCorner, borderPaint)

            val contentLeft = cardRect.left + cardPadding
            var cursorY = cardRect.top + cardPadding
            val footerTop =
                if (showFooter) {
                    cardRect.bottom - cardPadding - footerBlockHeight
                } else {
                    cardRect.bottom - cardPadding
                }

            if (tagLayout != null) {
                drawLayout(canvas, tagLayout, contentLeft, cursorY)
                cursorY += tagLayout.height + dp(resources, 14f)
            }
            if (titleLayout != null) {
                drawLayout(canvas, titleLayout, contentLeft, cursorY)
                cursorY += titleLayout.height + dp(resources, 10f)
            }

            val codeBackgroundPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.tagBg
                    alpha = 66
                }
            val codeCorner = dp(resources, 10f)
            bodyRenderLines.forEachIndexed { index, line ->
                val maxBodyBottom = footerTop
                if (cursorY + line.height > maxBodyBottom) return@forEachIndexed

                when (line.type) {
                    ShareBodyLineType.Blank -> {
                        cursorY += line.height
                    }

                    ShareBodyLineType.Code -> {
                        val top = cursorY
                        val bottom = cursorY + line.height
                        val codeRect = RectF(contentLeft, top, contentLeft + contentWidth, bottom)
                        canvas.drawRoundRect(codeRect, codeCorner, codeCorner, codeBackgroundPaint)
                        drawLayout(
                            canvas = canvas,
                            layout = line.layout,
                            x = contentLeft + codeHorizontalPadding,
                            y = cursorY + codeVerticalPadding,
                        )
                        cursorY += line.height
                    }

                    else -> {
                        drawLayout(canvas, line.layout, contentLeft, cursorY)
                        cursorY += line.height
                    }
                }
                if (index != bodyRenderLines.lastIndex) {
                    cursorY += lineSpacing
                }
            }

            if (showFooter) {
                val dividerY = footerTop + dp(resources, 18f)
                val dividerPaint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.divider
                        strokeWidth = dp(resources, 1f)
                    }
                canvas.drawLine(contentLeft, dividerY, contentLeft + contentWidth, dividerY, dividerPaint)

                val rowTop = dividerY + dp(resources, 12f)
                val baseline = rowTop - footerPaint.fontMetrics.ascent
                if (showTime) {
                    canvas.drawText(createdAtText, contentLeft, baseline, footerPaint)
                }
                if (activeDayCountText.isNotBlank()) {
                    val textWidth = footerPaint.measureText(activeDayCountText)
                    canvas.drawText(
                        activeDayCountText,
                        contentLeft + contentWidth - textWidth,
                        baseline,
                        footerPaint,
                    )
                }
            }

            return bitmap
        }

        private data class RenderLine(
            val type: ShareBodyLineType,
            val layout: StaticLayout,
            val height: Float,
        )

        private enum class ShareBodyLineType {
            Paragraph,
            Bullet,
            Quote,
            Code,
            Blank,
        }

        private data class ShareBodyLine(
            val text: String,
            val type: ShareBodyLineType,
        )

        private data class ShareCardPalette(
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

        private fun createTextPaint(
            color: Int,
            textSizePx: Float,
            typeface: Typeface,
        ): TextPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                this.textSize = textSizePx
                this.typeface = typeface
            }

        private fun buildStaticLayout(
            text: String,
            paint: TextPaint,
            width: Int,
            maxLines: Int = Int.MAX_VALUE,
        ): StaticLayout =
            StaticLayout
                .Builder
                .obtain(text.ifEmpty { " " }, 0, text.ifEmpty { " " }.length, paint, width.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.1f)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()

        private fun drawLayout(
            canvas: Canvas,
            layout: StaticLayout,
            x: Float,
            y: Float,
        ) {
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
        }

        private fun dp(
            resources: android.content.res.Resources,
            value: Float,
        ): Float = value * resources.displayMetrics.density

        private fun sp(
            resources: android.content.res.Resources,
            value: Float,
        ): Float = value * resources.displayMetrics.scaledDensity

        private fun formatShareCardTime(createdAtMillis: Long): String =
            shareCardTimeFormatter.format(
                Instant
                    .ofEpochMilli(createdAtMillis)
                    .atZone(ZoneId.systemDefault()),
            )

        private fun buildShareBodyLines(bodyText: String): List<ShareBodyLine> {
            if (bodyText.isBlank()) return listOf(ShareBodyLine("", ShareBodyLineType.Paragraph))

            val lines = mutableListOf<ShareBodyLine>()
            var previousWasBlank = false

            bodyText
                .replace('\t', ' ')
                .lineSequence()
                .forEach { rawLine ->
                    if (lines.size >= MAX_SHARE_BODY_LINES) return@forEach

                    val line = rawLine.trimEnd()
                    val trimmed = line.trimStart()

                    if (trimmed.isBlank()) {
                        if (!previousWasBlank && lines.isNotEmpty()) {
                            lines += ShareBodyLine("", ShareBodyLineType.Blank)
                        }
                        previousWasBlank = true
                        return@forEach
                    }

                    val typedLine =
                        when {
                            line.startsWith("    ") -> {
                                ShareBodyLine(trimmed, ShareBodyLineType.Code)
                            }

                            trimmed.startsWith("│ ") -> {
                                ShareBodyLine(
                                    trimmed.removePrefix("│ ").trim().normalizeCjkMixedSpacingForDisplay(),
                                    ShareBodyLineType.Quote,
                                )
                            }

                            trimmed.startsWith("☐") || trimmed.startsWith("☑") || trimmed.startsWith("• ") -> {
                                ShareBodyLine(trimmed.normalizeCjkMixedSpacingForDisplay(), ShareBodyLineType.Bullet)
                            }

                            else -> {
                                ShareBodyLine(trimmed.normalizeCjkMixedSpacingForDisplay(), ShareBodyLineType.Paragraph)
                            }
                        }
                    lines += typedLine
                    previousWasBlank = false
                }

            if (lines.isEmpty()) return listOf(ShareBodyLine("", ShareBodyLineType.Paragraph))
            return lines
        }

        private fun resolvePalette(style: String): ShareCardPalette =
            when (style) {
                "clean" -> {
                    ShareCardPalette(
                        bgStart = 0xFFF6F8FF.toInt(),
                        bgEnd = 0xFFE7EEFF.toInt(),
                        card = 0xFFFFFFFF.toInt(),
                        cardBorder = 0x2E5F86CB,
                        bodyText = 0xFF1D2A43.toInt(),
                        secondaryText = 0xFF687897.toInt(),
                        tagBg = 0x1F4E7ECB,
                        tagText = 0xFF385EAA.toInt(),
                        divider = 0x334F79C4,
                    )
                }

                "dark" -> {
                    ShareCardPalette(
                        bgStart = 0xFF111722.toInt(),
                        bgEnd = 0xFF0B1118.toInt(),
                        card = 0xFF1C2530.toInt(),
                        cardBorder = 0x3F86A2C8,
                        bodyText = 0xFFEAF2FB.toInt(),
                        secondaryText = 0xFF93A7BF.toInt(),
                        tagBg = 0x326786B0,
                        tagText = 0xFFD7E7FF.toInt(),
                        divider = 0x336E8EB6,
                    )
                }

                else -> {
                    ShareCardPalette(
                        bgStart = 0xFFFFF6E5.toInt(),
                        bgEnd = 0xFFF9E5C1.toInt(),
                        card = 0xFFFFFEFA.toInt(),
                        cardBorder = 0x33BE9350,
                        bodyText = 0xFF2F2414.toInt(),
                        secondaryText = 0xFF8A7962.toInt(),
                        tagBg = 0x24C89A4B,
                        tagText = 0xFF6A4E1E.toInt(),
                        divider = 0x33B18847,
                    )
                }
            }

        private companion object {
            const val MAX_SHARE_BITMAP_HEIGHT_PX = 4096
            const val MAX_SHARE_BODY_LINES = 60
        }
    }
