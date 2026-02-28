package com.lomo.app.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.lomo.app.R
import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.text.scriptAwareTextAlign
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Utility object for sharing and copying memo content.
 */
object ShareUtils {
    private const val TAG = "ShareUtils"
    private const val MAX_SHARE_CONTENT_CHARS = 4000
    private const val MAX_SHARE_BITMAP_HEIGHT_PX = 4096
    private const val MAX_SHARE_BODY_LINES = 60

    private data class ShareCardConfig(
        val style: String,
        val showTime: Boolean,
        val timestampMillis: Long?,
        val tags: List<String>,
        val activeDayCount: Int?,
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
        val shadow: Int,
        val surfaceHighlightStart: Int,
        val surfaceHighlightEnd: Int,
    )

    /**
     * Share memo content as an image card via Android share sheet.
     */
    fun shareMemoAsImage(
        context: Context,
        content: String,
        title: String? = null,
        hostView: View? = null,
        style: String = "warm",
        showTime: Boolean = true,
        timestamp: Long? = null,
        tags: List<String> = emptyList(),
        activeDayCount: Int? = null,
    ) {
        runCatching {
            val imageUri =
                createShareImageUri(
                    context = context,
                    content = content,
                    title = title,
                    hostView = hostView,
                    config =
                        ShareCardConfig(
                            style = style,
                            showTime = showTime,
                            timestampMillis = timestamp,
                            tags = tags,
                            activeDayCount = activeDayCount,
                        ),
                )
            val sendIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, imageUri)
                    title?.let { putExtra(Intent.EXTRA_TITLE, it) }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(context.contentResolver, "memo_image", imageUri)
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(Intent.createChooser(sendIntent, null))
        }.onFailure { throwable ->
            Log.e(TAG, "shareMemoAsImage failed, fallback to text share", throwable)
            // Fallback to text share so user still can complete the action.
            shareMemoText(context, content, title)
        }
    }

    /**
     * Share memo content via Android share sheet as plain text.
     */
    fun shareMemoText(
        context: Context,
        content: String,
        title: String? = null,
    ) {
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, content)
                type = "text/plain"
                title?.let { putExtra(Intent.EXTRA_TITLE, it) }
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(Intent.createChooser(sendIntent, null))
    }

    /**
     * Copy content to clipboard and show a toast confirmation.
     */
    fun copyToClipboard(
        context: Context,
        content: String,
        showToast: Boolean = true,
    ) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Lomo Memo", content)
        clipboardManager.setPrimaryClip(clip)

        if (showToast) {
            Toast
                .makeText(
                    context,
                    context.getString(R.string.copied_to_clipboard),
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    /**
     * Share memo as markdown file (for future enhancement).
     */
    fun shareMemoAsMarkdown(
        context: Context,
        content: String,
        fileName: String,
    ) {
        // TODO: Create temp file and share via FileProvider
        // For now, just share as text
        shareMemoText(context, content, fileName)
    }

    private fun createShareImageUri(
        context: Context,
        content: String,
        title: String?,
        hostView: View?,
        config: ShareCardConfig,
    ): android.net.Uri {
        val bitmap = createMemoCardBitmap(context, content, title, hostView, config)
        val dir = File(context.cacheDir, "shared_memos").apply { mkdirs() }
        val file = File(dir, "memo_share_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    private fun createMemoCardBitmap(
        context: Context,
        content: String,
        title: String?,
        hostView: View?,
        config: ShareCardConfig,
    ): Bitmap =
        createMemoCardBitmapWithCompose(
            context = context,
            content = content,
            title = title,
            hostView = hostView,
            config = config,
        )

    private fun createMemoCardBitmapWithCompose(
        context: Context,
        content: String,
        title: String?,
        hostView: View?,
        config: ShareCardConfig,
    ): Bitmap {
        val resources = context.resources
        val tags = buildShareTags(config.tags, content)
        val safeText = prepareShareBodyText(context, content, tags)
        val palette = resolvePalette(config.style)
        val createdAtMillis = config.timestampMillis ?: System.currentTimeMillis()
        val createdAtText =
            DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(createdAtMillis))
        val activeDayCountText =
            config.activeDayCount
                ?.takeIf { it > 0 }
                ?.let { dayCount ->
                    context.resources.getQuantityString(R.plurals.share_card_recorded_days, dayCount, dayCount)
                }.orEmpty()
        val showFooter = config.showTime || activeDayCountText.isNotBlank()

        val canvasWidth = (resources.displayMetrics.widthPixels.coerceAtLeast(720) * 0.9f).toInt()
        val composeView =
            ComposeView(context).apply {
                // Prefer exact owners from current host view; fallback to activity-level owners.
                val lifecycleOwner =
                    hostView?.getTag(androidx.lifecycle.runtime.R.id.view_tree_lifecycle_owner) ?: context.findComponentActivity()
                val viewModelStoreOwner =
                    hostView?.getTag(androidx.lifecycle.viewmodel.R.id.view_tree_view_model_store_owner)
                        ?: context.findComponentActivity()
                val savedStateOwner =
                    hostView?.getTag(androidx.savedstate.R.id.view_tree_saved_state_registry_owner)
                        ?: context.findComponentActivity()
                setTag(androidx.lifecycle.runtime.R.id.view_tree_lifecycle_owner, lifecycleOwner)
                setTag(androidx.lifecycle.viewmodel.R.id.view_tree_view_model_store_owner, viewModelStoreOwner)
                setTag(androidx.savedstate.R.id.view_tree_saved_state_registry_owner, savedStateOwner)
                hostView?.let { source ->
                    layoutDirection = source.layoutDirection
                    if (source is ViewGroup) {
                        clipChildren = source.clipChildren
                    }
                }
                setContent {
                    ShareCardLayout(
                        title = title,
                        bodyText = safeText,
                        tags = tags,
                        showTime = config.showTime,
                        createdAtText = createdAtText,
                        activeDayCountText = activeDayCountText,
                        showFooter = showFooter,
                        palette = palette,
                    )
                }
            }

        val attachParent = resolveOffscreenRenderParent(context, hostView)
        if (attachParent != null) {
            composeView.alpha = 0f
            composeView.visibility = View.INVISIBLE
            composeView.layoutParams =
                ViewGroup.LayoutParams(
                    canvasWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            attachParent.addView(composeView)
        }

        return try {
            val widthSpec = View.MeasureSpec.makeMeasureSpec(canvasWidth, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            composeView.measure(widthSpec, heightSpec)
            var measuredHeight =
                composeView.measuredHeight
                    .coerceAtLeast((resources.displayMetrics.density * 220f).toInt())
                    .coerceAtMost(MAX_SHARE_BITMAP_HEIGHT_PX)
            composeView.layout(0, 0, canvasWidth, measuredHeight)

            // Measure/layout once again so an off-screen first composition can settle before capture.
            composeView.measure(widthSpec, heightSpec)
            measuredHeight =
                composeView.measuredHeight
                    .coerceAtLeast((resources.displayMetrics.density * 220f).toInt())
                    .coerceAtMost(MAX_SHARE_BITMAP_HEIGHT_PX)
            composeView.layout(0, 0, canvasWidth, measuredHeight)

            Bitmap.createBitmap(canvasWidth, measuredHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                composeView.draw(canvas)
            }
        } finally {
            (composeView.parent as? ViewGroup)?.removeView(composeView)
            composeView.disposeComposition()
        }
    }

    private fun prepareShareBodyText(
        context: Context,
        content: String,
        tags: List<String>,
    ): String {
        val bodyTextWithoutTags = removeInlineTags(content, tags)
        val renderedMarkdownText = renderMarkdownForShare(context, bodyTextWithoutTags)
        return renderedMarkdownText
            .trim()
            .ifEmpty { context.getString(R.string.app_name) }
            .let {
                if (it.length <= MAX_SHARE_CONTENT_CHARS) {
                    it
                } else {
                    it.take(MAX_SHARE_CONTENT_CHARS) + "\n..."
                }
            }
    }

    private fun resolveOffscreenRenderParent(
        context: Context,
        hostView: View?,
    ): ViewGroup? {
        val hostRoot = hostView?.rootView as? ViewGroup
        if (hostRoot != null) return hostRoot
        return context.findComponentActivity()?.window?.decorView as? ViewGroup
    }

    private tailrec fun Context.findComponentActivity(): ComponentActivity? =
        when (this) {
            is ComponentActivity -> this
            is ContextWrapper -> baseContext.findComponentActivity()
            else -> null
        }

    @Composable
    private fun ShareCardLayout(
        title: String?,
        bodyText: String,
        tags: List<String>,
        showTime: Boolean,
        createdAtText: String,
        activeDayCountText: String,
        showFooter: Boolean,
        palette: ShareCardPalette,
    ) {
        val bodyTextSize =
            when {
                bodyText.length <= 32 -> 27.sp
                bodyText.length <= 88 -> 22.sp
                bodyText.length <= 180 -> 18.sp
                else -> 16.sp
            }
        val bodyLines = remember(bodyText) { buildShareBodyLines(bodyText) }
        val secondaryTextColor = Color(palette.secondaryText)

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        brush =
                            Brush.linearGradient(
                                colors =
                                    listOf(
                                        Color(palette.bgStart),
                                        Color(palette.bgEnd),
                                    ),
                            ),
                    ).padding(24.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 330.dp)
                        .border(
                            width = 1.dp,
                            color = Color(palette.cardBorder),
                            shape = RoundedCornerShape(28.dp),
                        ).background(
                            color = Color(palette.card),
                            shape = RoundedCornerShape(28.dp),
                        ).padding(26.dp),
                verticalArrangement =
                    if (showFooter) {
                        Arrangement.SpaceBetween
                    } else {
                        Arrangement.Top
                    },
            ) {
                Column {
                    if (tags.isNotEmpty()) {
                        Text(
                            text = tags.take(6).joinToString(separator = "   ") { "#$it" },
                            color = Color(palette.tagText),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    if (!title.isNullOrBlank()) {
                        val titleStyle =
                            TextStyle(
                                color = secondaryTextColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 20.sp,
                            ).scriptAwareFor(title)
                        Text(
                            text = title,
                            style = titleStyle,
                            textAlign = title.scriptAwareTextAlign(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    ShareCardBodyText(
                        lines = bodyLines,
                        bodyTextSize = bodyTextSize,
                        palette = palette,
                    )
                }

                if (showFooter) {
                    Column {
                        Spacer(modifier = Modifier.height(18.dp))
                        HorizontalDivider(color = Color(palette.divider))
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (showTime) {
                                Text(
                                    text = createdAtText,
                                    color = Color(palette.secondaryText),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }

                            if (activeDayCountText.isNotBlank()) {
                                Text(
                                    text = activeDayCountText,
                                    color = Color(palette.secondaryText),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

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

    @Composable
    private fun ShareCardBodyText(
        lines: List<ShareBodyLine>,
        bodyTextSize: androidx.compose.ui.unit.TextUnit,
        palette: ShareCardPalette,
    ) {
        val bodyColor = Color(palette.bodyText)
        val secondaryColor = Color(palette.secondaryText)
        val codeBackground = Color(palette.tagBg).copy(alpha = 0.16f)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { line ->
                when (line.type) {
                    ShareBodyLineType.Blank -> {
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    ShareBodyLineType.Code -> {
                        val codeStyle =
                            TextStyle(
                                color = bodyColor.copy(alpha = 0.92f),
                                fontSize = (bodyTextSize.value * 0.84f).sp,
                                lineHeight = (bodyTextSize.value * 1.35f).sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                            ).scriptAwareFor(line.text)
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(codeBackground, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = line.text,
                                style = codeStyle,
                                textAlign = line.text.scriptAwareTextAlign(),
                            )
                        }
                    }

                    ShareBodyLineType.Quote -> {
                        val quoteStyle =
                            TextStyle(
                                color = secondaryColor,
                                fontSize = (bodyTextSize.value * 0.9f).sp,
                                lineHeight = (bodyTextSize.value * 1.45f).sp,
                                fontWeight = FontWeight.Normal,
                            ).scriptAwareFor(line.text)
                        Text(
                            text = line.text,
                            style = quoteStyle,
                            textAlign = line.text.scriptAwareTextAlign(),
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }

                    ShareBodyLineType.Bullet -> {
                        val bulletStyle =
                            TextStyle(
                                color = bodyColor,
                                fontSize = (bodyTextSize.value * 0.94f).sp,
                                lineHeight = (bodyTextSize.value * 1.45f).sp,
                                fontWeight = FontWeight.Medium,
                            ).scriptAwareFor(line.text)
                        Text(
                            text = line.text,
                            style = bulletStyle,
                            textAlign = line.text.scriptAwareTextAlign(),
                        )
                    }

                    ShareBodyLineType.Paragraph -> {
                        val paragraphStyle =
                            TextStyle(
                                color = bodyColor,
                                fontSize = bodyTextSize,
                                lineHeight = (bodyTextSize.value * 1.48f).sp,
                                fontWeight = FontWeight.Normal,
                            ).scriptAwareFor(line.text)
                        Text(
                            text = line.text,
                            style = paragraphStyle,
                            textAlign = line.text.scriptAwareTextAlign(),
                        )
                    }
                }
            }
        }
    }

    private fun buildShareTags(
        sourceTags: List<String>,
        content: String,
    ): List<String> {
        val normalized =
            sourceTags
                .asSequence()
                .map { it.trim().trimStart('#') }
                .filter { it.isNotBlank() }
                .map { it.take(18) }
                .distinct()
                .take(6)
                .toList()
        if (normalized.isNotEmpty()) return normalized

        val regex = Regex("(?:^|\\s)#([\\p{L}\\p{N}_][\\p{L}\\p{N}_/]*)")
        return regex
            .findAll(content)
            .map { it.groupValues[1] }
            .map { it.take(18) }
            .distinct()
            .take(6)
            .toList()
    }

    private fun removeInlineTags(
        content: String,
        tags: List<String>,
    ): String {
        val explicitTags =
            tags
                .asSequence()
                .map { it.trim().trimStart('#') }
                .filter { it.isNotBlank() }
                .toList()

        var stripped = content
        explicitTags.forEach { tag ->
            val escaped = Regex.escape(tag)
            stripped =
                stripped.replace(Regex("(^|\\s)#$escaped(?=\\s|$)")) { match ->
                    if (match.value.startsWith(" ") || match.value.startsWith("\t")) " " else ""
                }
        }

        // Fallback generic cleanup in case tags were parsed from content rather than sourceTags.
        stripped =
            stripped.replace(Regex("(^|\\s)#[\\p{L}\\p{N}_][\\p{L}\\p{N}_/]*")) { match ->
                if (match.value.startsWith(" ") || match.value.startsWith("\t")) " " else ""
            }
        stripped = stripped.replace(Regex(" {2,}"), " ")
        stripped = stripped.replace(Regex("\\n{3,}"), "\n\n")
        return stripped.trim()
    }

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
                            ShareBodyLine(trimmed.removePrefix("│ ").trim().normalizeCjkMixedSpacingForDisplay(), ShareBodyLineType.Quote)
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

    private fun renderMarkdownForShare(
        context: Context,
        content: String,
    ): String {
        var str = content.replace("\r\n", "\n")

        // Preserve heading intent in plain-text rendering.
        str = str.replace(Regex("(?m)^\\s*#{1,2}\\s+"), "✦ ")
        str = str.replace(Regex("(?m)^\\s*#{3,6}\\s+"), "• ")

        // Convert fenced code blocks to indented plain text blocks.
        str =
            str.replace(Regex("```[\\w-]*\\n([\\s\\S]*?)```")) { match ->
                val code = match.groupValues[1].trim('\n')
                if (code.isBlank()) {
                    ""
                } else {
                    code
                        .lineSequence()
                        .joinToString("\n") { "    $it" }
                }
            }

        // Convert audio markdown attachments before generic markdown stripping.
        str =
            str.replace(
                Regex("!\\[[^\\]]*\\]\\(([^)]+\\.(?:m4a|mp3|aac|wav))\\)", RegexOption.IGNORE_CASE),
                context.getString(R.string.share_card_placeholder_audio),
            )

        str = stripMarkdownForShare(str)
        str = str.replace("[Image]", context.getString(R.string.share_card_placeholder_image))
        str =
            str.replace(Regex("\\[Image:\\s*(.*?)]")) { match ->
                context.getString(R.string.share_card_placeholder_image_named, match.groupValues[1])
            }
        str = str.replace(Regex("`([^`]+)`"), "「$1」")
        str = str.replace(Regex("~~(.*?)~~"), "$1")
        str = str.replace(Regex("(?m)^>\\s?"), "│ ")
        str = str.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "• ")
        str = str.replace(Regex("(?m)^\\s*[-+*]\\s+"), "• ")
        str = str.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), "")

        str =
            str
                .lineSequence()
                .joinToString("\n") { line ->
                    val trimmedRight = line.trimEnd()
                    if (trimmedRight.startsWith("    ")) {
                        trimmedRight
                    } else {
                        trimmedRight.replace(Regex(" {2,}"), " ")
                    }
                }
        str = str.replace(Regex("\\n{3,}"), "\n\n")
        return str.trim()
    }

    private fun stripMarkdownForShare(content: String): String {
        var str = content
        str = str.replace(Regex("(?m)^#{1,6}\\s+"), "")
        str = str.replace(Regex("(\\*\\*|__)"), "")
        str = str.replace(Regex("(?m)^\\s*[-*+]\\s*\\[ \\]"), "☐")
        str = str.replace(Regex("(?m)^\\s*[-*+]\\s*\\[x\\]"), "☑")
        str = str.replace(Regex("!\\[.*?\\]\\(.*?\\)"), "[Image]")
        str = str.replace(Regex("!\\[\\[(.*?)\\]\\]"), "[Image: $1]")
        str = str.replace(Regex("(?<!!)\\[(.*?)\\]\\(.*?\\)"), "$1")
        str = str.replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
        return str.trim()
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
                    shadow = 0x17000000,
                    surfaceHighlightStart = 0x26FFFFFF,
                    surfaceHighlightEnd = 0x00FFFFFF,
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
                    shadow = 0x31000000,
                    surfaceHighlightStart = 0x16CFE1FF,
                    surfaceHighlightEnd = 0x00CFE1FF,
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
                    shadow = 0x18000000,
                    surfaceHighlightStart = 0x1AFFFFFF,
                    surfaceHighlightEnd = 0x00FFFFFF,
                )
            }
        }
}
