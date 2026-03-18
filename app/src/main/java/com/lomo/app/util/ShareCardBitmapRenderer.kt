package com.lomo.app.util

import android.content.res.Configuration
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.os.Build
import android.util.TypedValue
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
import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
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
            showTime: Boolean,
            timestampMillis: Long?,
            tags: List<String>,
            activeDayCount: Int?,
            resolvedImagePaths: List<String> = emptyList(),
        ): Bitmap {
            val resources = context.resources
            val imagePlaceholder = context.getString(R.string.share_card_placeholder_image)

            // Pre-process: replace image markdown with indexed markers so images survive formatting
            var nextImageIndex = 0
            val hasImages = resolvedImagePaths.isNotEmpty()
            val contentForProcessing =
                if (hasImages) {
                    var result = content
                    result =
                        WIKI_IMAGE_REGEX.replace(result) {
                            "\n$IMAGE_MARKER_PREFIX${nextImageIndex++}$IMAGE_MARKER_SUFFIX\n"
                        }
                    result =
                        MD_IMAGE_REGEX.replace(result) { match ->
                            val path = match.groupValues[2]
                            if (AUDIO_EXTENSIONS.any { path.lowercase().endsWith(it) }) {
                                match.value
                            } else {
                                "\n$IMAGE_MARKER_PREFIX${nextImageIndex++}$IMAGE_MARKER_SUFFIX\n"
                            }
                        }
                    result
                } else {
                    content
                }
            val totalImageSlots = nextImageIndex

            val shareCardContent =
                prepareShareCardContentUseCase(
                    ShareCardTextInput(
                        content = contentForProcessing,
                        sourceTags = tags,
                    ),
                )
            val displayTags = shareCardDisplayFormatter.formatTagsForDisplay(shareCardContent.tags)
            val safeText =
                shareCardDisplayFormatter
                    .formatBodyText(
                        bodyText = shareCardContent.bodyText,
                        audioPlaceholder = context.getString(R.string.share_card_placeholder_audio),
                        imagePlaceholder = imagePlaceholder,
                        imageNamedPlaceholderPattern = context.getString(R.string.share_card_placeholder_image_named),
                    ).ifBlank {
                        context.getString(R.string.app_name)
                    }
            val palette = resolvePalette(context)
            val createdAtMillis = timestampMillis ?: System.currentTimeMillis()
            val createdAtText = formatShareCardTime(createdAtMillis)
            val activeDayCountText =
                activeDayCount
                    ?.takeIf { it > 0 }
                    ?.let { dayCount ->
                        context.resources.getQuantityString(R.plurals.share_card_recorded_days, dayCount, dayCount)
                    }.orEmpty()
            val showFooter = showTime || activeDayCountText.isNotBlank()

            // --- Typography ---
            val textLengthWithoutMarkers =
                if (hasImages) {
                    IMAGE_MARKER_PATTERN.replace(safeText, "").length
                } else {
                    safeText.length
                }
            val shareBodyLines = buildShareBodyLines(safeText, imagePlaceholder)
            val shouldUseCenteredBody =
                !hasImages &&
                    displayTags.isEmpty() &&
                    title.isNullOrBlank() &&
                    textLengthWithoutMarkers <= SHORT_BODY_CENTER_THRESHOLD &&
                    shareBodyLines.count { it.type != ShareBodyLineType.Blank } <= SHORT_BODY_MAX_NON_BLANK_LINES &&
                    shareBodyLines.all { it.type == ShareBodyLineType.Paragraph || it.type == ShareBodyLineType.Blank }
            val bodyTextSizeSp =
                when {
                    textLengthWithoutMarkers <= 32 -> 22f
                    textLengthWithoutMarkers <= 88 -> 20f
                    textLengthWithoutMarkers <= 180 -> 17f
                    else -> 15.5f
                }

            val canvasWidth = (resources.displayMetrics.widthPixels.coerceAtLeast(720) * 0.9f).roundToInt()
            val outerPadding = dp(resources, 24f)
            val cardPadding = dp(resources, 30f)
            val cardCorner = dp(resources, 24f)
            val lineSpacing = dp(resources, 7f)
            val codeHorizontalPadding = dp(resources, 12f)
            val codeVerticalPadding = dp(resources, 10f)
            val imageCorner = dp(resources, 12f)
            val imageVerticalPadding = dp(resources, 4f)
            val maxImageHeightPx = dp(resources, 360f)
            val minCardHeight = dp(resources, 330f)
            val contentWidth =
                (canvasWidth - outerPadding * 2 - cardPadding * 2)
                    .roundToInt()
                    .coerceAtLeast(1)

            val tagPaint =
                createTextPaint(
                    color = palette.tagText,
                    textSizePx = sp(resources, 11.5f),
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
                ).apply { letterSpacing = 0.03f }
            val titlePaint =
                createTextPaint(
                    color = palette.secondaryText,
                    textSizePx = sp(resources, 13.5f),
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
                ).apply { letterSpacing = 0.01f }
            val paragraphPaint =
                createTextPaint(
                    color = palette.bodyText,
                    textSizePx = sp(resources, bodyTextSizeSp),
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
                ).apply {
                    letterSpacing = if (shouldUseCenteredBody) 0.01f else 0.015f
                }
            val bulletPaint =
                createTextPaint(
                    color = palette.bodyText,
                    textSizePx = sp(resources, bodyTextSizeSp * 0.94f),
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
                ).apply { letterSpacing = 0.01f }
            val quotePaint =
                createTextPaint(
                    color = palette.secondaryText,
                    textSizePx = sp(resources, bodyTextSizeSp * 0.92f),
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
                ).apply { letterSpacing = 0.01f }
            val codePaint =
                createTextPaint(
                    color = palette.bodyText,
                    textSizePx = sp(resources, bodyTextSizeSp * 0.84f),
                    typeface = Typeface.MONOSPACE,
                )
            val footerPaint =
                createTextPaint(
                    color = palette.secondaryText,
                    textSizePx = sp(resources, 12f),
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
                ).apply { letterSpacing = 0.02f }

            // --- Load images ---
            val loadedImages = mutableMapOf<Int, Bitmap>()
            if (hasImages) {
                for (i in 0 until min(totalImageSlots, resolvedImagePaths.size)) {
                    loadShareImage(context, resolvedImagePaths[i], contentWidth)?.let {
                        loadedImages[i] = it
                    }
                }
            }

            // --- Build layouts ---
            val tagLayout =
                displayTags
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = "  \u00B7  ") { "#$it" }
                    ?.let { buildStaticLayout(it, tagPaint, contentWidth, maxLines = 2) }
            val titleLayout =
                title
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { buildStaticLayout(it, titlePaint, contentWidth, maxLines = 1) }
            val bodyRenderLines =
                shareBodyLines.map { line ->
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

                        ShareBodyLineType.Image -> {
                            val imgBitmap = loadedImages[line.imageIndex]
                            if (imgBitmap != null) {
                                val scale = contentWidth.toFloat() / imgBitmap.width
                                val drawHeight = (imgBitmap.height * scale).coerceAtMost(maxImageHeightPx)
                                RenderLine(
                                    type = ShareBodyLineType.Image,
                                    layout = buildStaticLayout(" ", paragraphPaint, contentWidth),
                                    height = drawHeight + imageVerticalPadding * 2,
                                    imageBitmap = imgBitmap,
                                    imageDrawWidth = contentWidth.toFloat(),
                                    imageDrawHeight = drawHeight,
                                )
                            } else {
                                // Fallback to placeholder text
                                val layout = buildStaticLayout(imagePlaceholder, paragraphPaint, contentWidth)
                                RenderLine(type = ShareBodyLineType.Paragraph, layout = layout, height = layout.height.toFloat())
                            }
                        }

                        ShareBodyLineType.Paragraph -> {
                            val layout =
                                buildStaticLayout(
                                    text = line.text,
                                    paint = paragraphPaint,
                                    width = contentWidth,
                                    alignment =
                                        if (shouldUseCenteredBody) {
                                            Layout.Alignment.ALIGN_CENTER
                                        } else {
                                            Layout.Alignment.ALIGN_NORMAL
                                        },
                                )
                            RenderLine(type = line.type, layout = layout, height = layout.height.toFloat())
                        }
                    }
                }

            // --- Measure total content height ---
            var contentHeight = 0f
            if (tagLayout != null) {
                contentHeight += tagLayout.height + dp(resources, 16f)
            }
            if (titleLayout != null) {
                contentHeight += titleLayout.height + dp(resources, 12f)
            }
            val bodyContentHeight =
                bodyRenderLines.foldIndexed(0f) { index, total, line ->
                    total + line.height + if (index != bodyRenderLines.lastIndex) lineSpacing else 0f
                }
            contentHeight += bodyContentHeight

            val footerTextHeight = footerPaint.fontMetrics.let { it.descent - it.ascent }
            val footerBlockHeight =
                if (showFooter) {
                    dp(resources, 20f) + dp(resources, 1f) + dp(resources, 14f) + footerTextHeight
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

            // --- Draw background ---
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

            // --- Draw card ---
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

            // --- Draw tags ---
            if (tagLayout != null) {
                drawLayout(canvas, tagLayout, contentLeft, cursorY)
                cursorY += tagLayout.height + dp(resources, 16f)
            }
            // --- Draw title ---
            if (titleLayout != null) {
                drawLayout(canvas, titleLayout, contentLeft, cursorY)
                cursorY += titleLayout.height + dp(resources, 12f)
            }
            if (shouldUseCenteredBody) {
                val bodyAreaHeight = (footerTop - cursorY).coerceAtLeast(0f)
                if (bodyContentHeight < bodyAreaHeight) {
                    cursorY += (bodyAreaHeight - bodyContentHeight) / 2f
                }
            }

            // --- Draw body ---
            val codeBackgroundPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.tagBg
                    alpha = 66
                }
            val codeCorner = dp(resources, 10f)
            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.save()
            canvas.clipRect(contentLeft, cursorY, contentLeft + contentWidth, footerTop)
            bodyRenderLines.forEachIndexed { index, line ->
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

                    ShareBodyLineType.Image -> {
                        val imgBitmap = line.imageBitmap
                        if (imgBitmap != null) {
                            val drawW = line.imageDrawWidth
                            val drawH = line.imageDrawHeight
                            cursorY += imageVerticalPadding
                            val dstRect = RectF(contentLeft, cursorY, contentLeft + drawW, cursorY + drawH)
                            canvas.save()
                            val clipPath = Path()
                            clipPath.addRoundRect(dstRect, imageCorner, imageCorner, Path.Direction.CW)
                            canvas.clipPath(clipPath)
                            canvas.drawBitmap(
                                imgBitmap,
                                null,
                                dstRect,
                                imagePaint,
                            )
                            canvas.restore()
                            cursorY += drawH + imageVerticalPadding
                        }
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
            canvas.restore()

            // --- Draw footer ---
            if (showFooter) {
                val dividerY = footerTop + dp(resources, 20f)
                val dividerPaint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.divider
                        strokeWidth = dp(resources, 1f)
                    }
                canvas.drawLine(contentLeft, dividerY, contentLeft + contentWidth, dividerY, dividerPaint)

                val rowTop = dividerY + dp(resources, 14f)
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

            // Clean up loaded bitmaps (they are intermediate scaled copies)
            loadedImages.values.forEach { it.recycle() }

            return bitmap
        }

        private fun loadShareImage(
            context: Context,
            path: String,
            targetWidth: Int,
        ): Bitmap? =
            try {
                val isContentUri = path.startsWith("content://")
                val isFileUri = path.startsWith("file://")
                val fileUriPath = if (isFileUri) parseUriPath(path) else null

                // First pass: get dimensions
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                when {
                    path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true) -> {
                        loadRemoteBitmap(path, options)
                    }
                    isContentUri -> {
                        val uri = Uri.parse(path)
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, options)
                        }
                    }
                    fileUriPath != null -> BitmapFactory.decodeFile(fileUriPath, options)
                    else -> BitmapFactory.decodeFile(path, options)
                }

                if (options.outWidth <= 0 || options.outHeight <= 0) return null

                // Calculate sample size for memory efficiency
                val sampleSize = max(1, options.outWidth / (targetWidth * 2))
                val decodeOptions =
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }

                // Second pass: decode bitmap
                val rawBitmap =
                    when {
                        path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true) -> {
                            loadRemoteBitmap(path, decodeOptions)
                        }
                        isContentUri -> {
                            val uri = Uri.parse(path)
                            context.contentResolver.openInputStream(uri)?.use {
                                BitmapFactory.decodeStream(it, null, decodeOptions)
                            }
                        }
                        fileUriPath != null -> BitmapFactory.decodeFile(fileUriPath, decodeOptions)
                        else -> BitmapFactory.decodeFile(path, decodeOptions)
                    } ?: return null

                // Scale to target width
                val scale = targetWidth.toFloat() / rawBitmap.width
                if (scale >= 0.95f && scale <= 1.05f) return rawBitmap // close enough, skip scaling

                val scaledWidth = targetWidth
                val scaledHeight = (rawBitmap.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(rawBitmap, scaledWidth, scaledHeight, true)
                if (scaled !== rawBitmap) rawBitmap.recycle()
                scaled
            } catch (e: Exception) {
                Timber.w(e, "Failed to load share image: %s", path)
                null
            }

        private fun loadRemoteBitmap(
            path: String,
            options: BitmapFactory.Options,
        ): Bitmap? {
            val connection =
                (URL(path).openConnection() as HttpURLConnection).apply {
                    connectTimeout = REMOTE_IMAGE_TIMEOUT_MS
                    readTimeout = REMOTE_IMAGE_TIMEOUT_MS
                    instanceFollowRedirects = true
                    doInput = true
                }
            return try {
                connection.connect()
                connection.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } finally {
                connection.disconnect()
            }
        }

        private fun parseUriPath(value: String): String? =
            runCatching {
                URI(value).path
            }.getOrNull()

        private data class RenderLine(
            val type: ShareBodyLineType,
            val layout: StaticLayout,
            val height: Float,
            val imageBitmap: Bitmap? = null,
            val imageDrawWidth: Float = 0f,
            val imageDrawHeight: Float = 0f,
        )

        private enum class ShareBodyLineType {
            Paragraph,
            Bullet,
            Quote,
            Code,
            Image,
            Blank,
        }

        private data class ShareBodyLine(
            val text: String,
            val type: ShareBodyLineType,
            val imageIndex: Int = -1,
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
                isSubpixelText = true
            }

        private fun buildStaticLayout(
            text: String,
            paint: TextPaint,
            width: Int,
            maxLines: Int = Int.MAX_VALUE,
            alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        ): StaticLayout =
            StaticLayout
                .Builder
                .obtain(text.ifEmpty { " " }, 0, text.ifEmpty { " " }.length, paint, width.coerceAtLeast(1))
                .setAlignment(alignment)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.3f)
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
        ): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                value,
                resources.displayMetrics,
            )

        private fun formatShareCardTime(createdAtMillis: Long): String =
            shareCardTimeFormatter.format(
                Instant
                    .ofEpochMilli(createdAtMillis)
                    .atZone(ZoneId.systemDefault()),
            )

        private fun buildShareBodyLines(
            bodyText: String,
            imagePlaceholder: String,
        ): List<ShareBodyLine> {
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

                    // Detect image markers
                    val markerMatch = IMAGE_MARKER_PATTERN.find(trimmed)
                    if (markerMatch != null && trimmed == markerMatch.value) {
                        val imageIndex = markerMatch.groupValues[1].toIntOrNull() ?: -1
                        lines += ShareBodyLine(trimmed, ShareBodyLineType.Image, imageIndex = imageIndex)
                        previousWasBlank = false
                        return@forEach
                    }

                    // Replace any inline markers with placeholder text
                    val cleanedTrimmed =
                        if (IMAGE_MARKER_PATTERN.containsMatchIn(trimmed)) {
                            IMAGE_MARKER_PATTERN.replace(trimmed, imagePlaceholder)
                        } else {
                            trimmed
                        }

                    val typedLine =
                        when {
                            line.startsWith("    ") -> {
                                ShareBodyLine(cleanedTrimmed, ShareBodyLineType.Code)
                            }

                            cleanedTrimmed.startsWith("│ ") -> {
                                ShareBodyLine(
                                    cleanedTrimmed.removePrefix("│ ").trim().normalizeCjkMixedSpacingForDisplay(),
                                    ShareBodyLineType.Quote,
                                )
                            }

                            cleanedTrimmed.startsWith("☐") ||
                                cleanedTrimmed.startsWith("☑") ||
                                cleanedTrimmed.startsWith("• ") -> {
                                ShareBodyLine(
                                    cleanedTrimmed.normalizeCjkMixedSpacingForDisplay(),
                                    ShareBodyLineType.Bullet,
                                )
                            }

                            else -> {
                                ShareBodyLine(
                                    cleanedTrimmed.normalizeCjkMixedSpacingForDisplay(),
                                    ShareBodyLineType.Paragraph,
                                )
                            }
                        }
                    lines += typedLine
                    previousWasBlank = false
                }

            if (lines.isEmpty()) return listOf(ShareBodyLine("", ShareBodyLineType.Paragraph))
            return lines
        }

        private fun resolvePalette(context: Context): ShareCardPalette =
            buildShareCardColorScheme(context).toShareCardPalette()

        private fun Context.isDarkTheme(): Boolean =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        @OptIn(ExperimentalMaterial3ExpressiveApi::class)
        private fun buildShareCardColorScheme(context: Context): ColorScheme {
            val darkTheme = context.isDarkTheme()
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme -> dynamicLightColorScheme(context)
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

        private companion object {
            const val MAX_SHARE_BITMAP_HEIGHT_PX = 4096
            const val MAX_SHARE_BODY_LINES = 60
            const val SHORT_BODY_CENTER_THRESHOLD = 42
            const val SHORT_BODY_MAX_NON_BLANK_LINES = 3
            const val REMOTE_IMAGE_TIMEOUT_MS = 10_000

            const val IMAGE_MARKER_PREFIX = "\uFFFCIMG"
            const val IMAGE_MARKER_SUFFIX = "\uFFFC"
            val IMAGE_MARKER_PATTERN = Regex("\uFFFCIMG(\\d+)\uFFFC")

            val WIKI_IMAGE_REGEX = Regex("!\\[\\[(.*?)\\]\\]")
            val MD_IMAGE_REGEX = Regex("!\\[(.*?)\\]\\((.*?)\\)")
            val AUDIO_EXTENSIONS = setOf(".m4a", ".mp3", ".aac", ".wav")
        }
    }
