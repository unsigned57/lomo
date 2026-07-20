package com.lomo.ui.component.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownRenderListItem
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.text.MemoTextSelectionRegistrar
import kotlinx.collections.immutable.toImmutableList

/** Compose renderer over the validated Rust-owned IR. This surface never accepts Markdown source. */
@Composable
fun MarkdownRenderer(
    document: MarkdownRenderDocument,
    modifier: Modifier = Modifier,
    maxVisibleBlocks: Int = Int.MAX_VALUE,
    onTodoClick: ((MarkdownSourceSpan) -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
    onTotalBlocks: ((Int) -> Unit)? = null,
    enableTextSelection: Boolean = false,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)? = null,
) {
    MarkdownIrRenderer(
        document = document,
        modifier = modifier,
        maxVisibleBlocks = maxVisibleBlocks,
        onTaskClick = onTodoClick,
        onImageClick = onImageClick,
        mediaPresentationResolver = mediaPresentationResolver,
        onTotalBlocks = onTotalBlocks,
        enableTextSelection = enableTextSelection,
        hideImages = hideImages,
        mediaContent = mediaContent,
    )
}

@Composable
internal fun MarkdownIrRenderer(
    document: MarkdownRenderDocument,
    modifier: Modifier = Modifier,
    maxVisibleBlocks: Int = Int.MAX_VALUE,
    onTaskClick: ((MarkdownSourceSpan) -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
    onTotalBlocks: ((Int) -> Unit)? = null,
    enableTextSelection: Boolean = false,
    textSelectionRegistrar: MemoTextSelectionRegistrar? = null,
    onTextTapFeedback: (() -> Unit)? = null,
    onTextBodyClick: (() -> Unit)? = null,
    onTextDoubleClick: (() -> Unit)? = null,
    onTextLongClick: (() -> Unit)? = null,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)? = null,
) {
    val plan = buildMarkdownIrPresentationPlan(document, maxVisibleBlocks)
    LaunchedEffect(plan.totalBlocks, onTotalBlocks) {
        onTotalBlocks?.invoke(plan.totalBlocks)
    }
    Column(modifier = modifier) {
        plan.items.forEach { item ->
            when (item) {
                is MarkdownIrPresentationItem.Block ->
                    MarkdownIrBlock(
                        block = item.block,
                        onTaskClick = onTaskClick,
                        onImageClick = onImageClick,
                        mediaPresentationResolver = mediaPresentationResolver,
                        enableTextSelection = enableTextSelection,
                        textSelectionRegistrar = textSelectionRegistrar,
                        onTextTapFeedback = onTextTapFeedback,
                        onTextBodyClick = onTextBodyClick,
                        onTextDoubleClick = onTextDoubleClick,
                        onTextLongClick = onTextLongClick,
                        hideImages = hideImages,
                        mediaContent = mediaContent,
                    )
                is MarkdownIrPresentationItem.Gallery ->
                    if (!hideImages) {
                        MarkdownImagePager(
                            images = item.images.map(MarkdownRenderInline.Image::toPresentationImage).toImmutableList(),
                            onImageClick = onImageClick,
                        )
                    }
            }
        }
    }
}

@Composable
private fun MarkdownIrBlock(
    block: MarkdownRenderBlock,
    onTaskClick: ((MarkdownSourceSpan) -> Unit)?,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    enableTextSelection: Boolean,
    textSelectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
    hideImages: Boolean,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    when (block) {
        is MarkdownRenderBlock.Paragraph ->
            MarkdownIrParagraph(
                inlines = block.inlines,
                onImageClick = onImageClick,
                mediaPresentationResolver = mediaPresentationResolver,
                enableTextSelection = enableTextSelection,
                textSelectionRegistrar = textSelectionRegistrar,
                onTextTapFeedback = onTextTapFeedback,
                onTextBodyClick = onTextBodyClick,
                onTextDoubleClick = onTextDoubleClick,
                onTextLongClick = onTextLongClick,
                hideImages = hideImages,
                mediaContent = mediaContent,
            )
        is MarkdownRenderBlock.Heading ->
            Text(
                text = block.inlines.toAnnotatedText(),
                style =
                    when (block.level) {
                        1u -> MaterialTheme.typography.headlineMedium
                        2u -> MaterialTheme.typography.headlineSmall
                        else -> MaterialTheme.typography.titleMedium
                    },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        is MarkdownRenderBlock.BlockQuote ->
            Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp)) {
                block.blocks.forEach { child ->
                    MarkdownIrBlock(
                        child,
                        onTaskClick,
                        onImageClick,
                        mediaPresentationResolver,
                        enableTextSelection,
                        textSelectionRegistrar,
                        onTextTapFeedback,
                        onTextBodyClick,
                        onTextDoubleClick,
                        onTextLongClick,
                        hideImages,
                        mediaContent,
                    )
                }
            }
        is MarkdownRenderBlock.ListBlock ->
            Column(modifier = Modifier.fillMaxWidth()) {
                block.items.forEachIndexed { index, item ->
                    MarkdownIrListItem(
                        item = item,
                        marker = if (block.ordered) "${block.startNumber + index.toULong()}." else "•",
                        onTaskClick = onTaskClick,
                        onImageClick = onImageClick,
                        mediaPresentationResolver = mediaPresentationResolver,
                        enableTextSelection = enableTextSelection,
                        textSelectionRegistrar = textSelectionRegistrar,
                        hideImages = hideImages,
                        mediaContent = mediaContent,
                    )
                }
            }
        is MarkdownRenderBlock.CodeBlock ->
            Text(
                text = block.literal,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        is MarkdownRenderBlock.ThematicBreak -> Text("────────", modifier = Modifier.fillMaxWidth())
        is MarkdownRenderBlock.Table ->
            Column(modifier = Modifier.fillMaxWidth()) {
                (listOf(block.header) + block.rows).forEach { row ->
                    Text(row.joinToString(" | ") { cell -> cell.inlines.toPlainText() })
                }
            }
        is MarkdownRenderBlock.HtmlBlock -> Text(block.literal, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun MarkdownIrListItem(
    item: MarkdownRenderListItem,
    marker: String,
    onTaskClick: ((MarkdownSourceSpan) -> Unit)?,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    enableTextSelection: Boolean,
    textSelectionRegistrar: MemoTextSelectionRegistrar?,
    hideImages: Boolean,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        val actionSpan = item.actionSpan
        val checked = item.checked
        if (actionSpan != null && checked != null) {
            Checkbox(
                checked = checked,
                onCheckedChange = if (onTaskClick == null) null else ({ onTaskClick(actionSpan) }),
            )
        } else {
            Text(marker, modifier = Modifier.width(28.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            item.blocks.forEach { child ->
                MarkdownIrBlock(
                    block = child,
                    onTaskClick = onTaskClick,
                    onImageClick = onImageClick,
                    mediaPresentationResolver = mediaPresentationResolver,
                    enableTextSelection = enableTextSelection,
                    textSelectionRegistrar = textSelectionRegistrar,
                    onTextTapFeedback = null,
                    onTextBodyClick = null,
                    onTextDoubleClick = null,
                    onTextLongClick = null,
                    hideImages = hideImages,
                    mediaContent = mediaContent,
                )
            }
        }
    }
}

@Composable
private fun MarkdownIrParagraph(
    inlines: List<MarkdownRenderInline>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    enableTextSelection: Boolean,
    textSelectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
    hideImages: Boolean,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    val images = inlines.filterIsInstance<MarkdownRenderInline.Image>()
    val text = inlines.filterNot { inline -> inline is MarkdownRenderInline.Image }.toAnnotatedText()
    if (text.isNotEmpty()) {
        MDText(
            text = text,
            style = null,
            enableTextSelection = enableTextSelection,
            selectionRegistrar = textSelectionRegistrar,
            onTapFeedback = onTextTapFeedback,
            onBodyClick = onTextBodyClick,
            onDoubleClick = onTextDoubleClick,
            onLongClick = onTextLongClick,
        )
    }
    if (!hideImages) {
        images.forEach { image ->
            val presentationImage = image.toPresentationImage()
            val media = mediaPresentationResolver?.invoke(presentationImage)
            if (media != null && mediaContent != null) {
                mediaContent(media)
            } else {
                MarkdownImageBlock(presentationImage, onImageClick)
            }
        }
    }
}

private fun MarkdownRenderInline.Image.toPresentationImage(): MarkdownPresentationImage =
    MarkdownPresentationImage(
        destination = destination,
        description = title ?: altText.takeIf(String::isNotBlank),
    )

private fun List<MarkdownRenderInline>.toPlainText(): String =
    buildString { this@toPlainText.forEach { inline -> append(inline.plainText()) } }

private fun MarkdownRenderInline.plainText(): String =
    when (this) {
        is MarkdownRenderInline.Text -> text
        is MarkdownRenderInline.Strong -> inlines.toPlainText()
        is MarkdownRenderInline.Emphasis -> inlines.toPlainText()
        is MarkdownRenderInline.Strikethrough -> inlines.toPlainText()
        is MarkdownRenderInline.Highlight -> inlines.toPlainText()
        is MarkdownRenderInline.Code -> text
        is MarkdownRenderInline.Link -> inlines.toPlainText()
        is MarkdownRenderInline.Image -> altText
        is MarkdownRenderInline.Tag -> "#$name"
        is MarkdownRenderInline.Reminder -> token
        is MarkdownRenderInline.WikiReference -> inlines.toPlainText().ifBlank { target }
        is MarkdownRenderInline.SoftBreak,
        is MarkdownRenderInline.HardBreak,
        -> "\n"
        is MarkdownRenderInline.HtmlInline -> literal
    }

private fun List<MarkdownRenderInline>.toAnnotatedText(): AnnotatedString {
    val builder = AnnotatedString.Builder()
    forEach { inline -> builder.appendInline(inline) }
    return builder.toAnnotatedString()
}

private fun AnnotatedString.Builder.appendInline(inline: MarkdownRenderInline) {
    when (inline) {
        is MarkdownRenderInline.Text -> append(inline.text)
        is MarkdownRenderInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold), inline.inlines)
        is MarkdownRenderInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic), inline.inlines)
        is MarkdownRenderInline.Strikethrough ->
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), inline.inlines)
        is MarkdownRenderInline.Highlight -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold), inline.inlines)
        is MarkdownRenderInline.Code -> append(inline.text)
        is MarkdownRenderInline.Link -> {
            pushLink(LinkAnnotation.Url(inline.destination))
            inline.inlines.forEach(::appendInline)
            pop()
        }
        is MarkdownRenderInline.Image -> Unit
        is MarkdownRenderInline.Tag -> append("#${inline.name}")
        is MarkdownRenderInline.Reminder -> append(inline.token)
        is MarkdownRenderInline.WikiReference ->
            if (inline.inlines.isEmpty()) append(inline.target) else inline.inlines.forEach(::appendInline)
        is MarkdownRenderInline.SoftBreak,
        is MarkdownRenderInline.HardBreak,
        -> append('\n')
        is MarkdownRenderInline.HtmlInline -> append(inline.literal)
    }
}

private fun AnnotatedString.Builder.withStyle(
    style: SpanStyle,
    children: List<MarkdownRenderInline>,
) {
    pushStyle(style)
    children.forEach(::appendInline)
    pop()
}
