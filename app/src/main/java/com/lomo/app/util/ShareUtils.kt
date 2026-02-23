package com.lomo.app.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.Toast
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.lomo.app.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility object for sharing and copying memo content.
 */
object ShareUtils {
    private const val MAX_SHARE_CONTENT_CHARS = 4000
    private val markdownTextProcessor =
        com.lomo.data.util
            .MemoTextProcessor()

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
        }.onFailure {
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
        config: ShareCardConfig,
    ): android.net.Uri {
        val bitmap = createMemoCardBitmap(context, content, title, config)
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
        config: ShareCardConfig,
    ): Bitmap =
        createMemoCardBitmapWithCompose(
            context = context,
            content = content,
            title = title,
            config = config,
        )

    private fun createMemoCardBitmapWithCompose(
        context: Context,
        content: String,
        title: String?,
        config: ShareCardConfig,
    ): Bitmap {
        val resources = context.resources
        val tags = buildShareTags(config.tags, content)
        val bodyTextWithoutTags = removeInlineTags(content, tags)
        val renderedMarkdownText = renderMarkdownForShare(context, bodyTextWithoutTags)
        val safeText =
            renderedMarkdownText
                .trim()
                .ifEmpty { context.getString(R.string.app_name) }
                .let {
                    if (it.length <= MAX_SHARE_CONTENT_CHARS) {
                        it
                    } else {
                        it.take(MAX_SHARE_CONTENT_CHARS) + "\n…"
                    }
                }
        val palette = resolvePalette(config.style)
        val createdAtMillis = config.timestampMillis ?: System.currentTimeMillis()
        val createdAtText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAtMillis))
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

        val widthSpec = View.MeasureSpec.makeMeasureSpec(canvasWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        composeView.measure(widthSpec, heightSpec)
        val measuredHeight = composeView.measuredHeight.coerceAtLeast((resources.displayMetrics.density * 220f).toInt())
        composeView.layout(0, 0, canvasWidth, measuredHeight)

        return Bitmap.createBitmap(canvasWidth, measuredHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            composeView.draw(canvas)
        }
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
            ) {
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
                    Text(
                        text = title,
                        color = Color(palette.secondaryText),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Text(
                    text = bodyText,
                    color = Color(palette.bodyText),
                    fontSize = bodyTextSize,
                    lineHeight = (bodyTextSize.value * 1.32f).sp,
                    fontWeight = FontWeight.Medium,
                )

                if (showFooter) {
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

    private fun renderMarkdownForShare(
        context: Context,
        content: String,
    ): String {
        var str = content.replace("\r\n", "\n")

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

        str = markdownTextProcessor.stripMarkdown(str)
        str = str.replace("[Image]", context.getString(R.string.share_card_placeholder_image))
        str =
            str.replace(Regex("\\[Image:\\s*(.*?)]")) { match ->
                context.getString(R.string.share_card_placeholder_image_named, match.groupValues[1])
            }
        str = str.replace(Regex("`([^`]+)`"), "「$1」")
        str = str.replace(Regex("~~(.*?)~~"), "$1")
        str = str.replace(Regex("(?m)^>\\s?"), "│ ")
        str = str.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), "")
        str = str.replace(Regex("\\n{3,}"), "\n\n")
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
