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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.MediaClass
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.text.MemoTextSelectionRegistrar

/** Compose renderer over the validated Rust-owned IR. This surface never accepts Markdown source. */
@Composable
fun MarkdownRenderer(
    document: MarkdownRenderDocument,
    modifier: Modifier = Modifier,
    presentationPlan: MarkdownIrPresentationPlan? = null,
    presentationPolicy: MarkdownPresentationPolicy = MarkdownPresentationPolicy.DEFAULT,
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
        presentationPlan = presentationPlan,
        presentationPolicy = presentationPolicy,
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
    presentationPlan: MarkdownIrPresentationPlan? = null,
    presentationPolicy: MarkdownPresentationPolicy = MarkdownPresentationPolicy.DEFAULT,
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
    val plan =
        presentationPlan
            ?: remember(document, maxVisibleBlocks, presentationPolicy) {
                buildMarkdownIrPresentationPlan(
                    document = document,
                    maxVisibleBlocks = maxVisibleBlocks,
                    policy = presentationPolicy,
                )
            }
    LaunchedEffect(plan.totalBlocks, onTotalBlocks) {
        onTotalBlocks?.invoke(plan.totalBlocks)
    }
    val context =
        MarkdownIrRenderContext(
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
    Column(modifier = modifier) {
        plan.items.forEach { item ->
            when (item) {
                is MarkdownIrPresentationItem.Block ->
                    MarkdownIrBlockDisplay(
                        display = item.display,
                        context = context,
                    )
                is MarkdownIrPresentationItem.Gallery ->
                    if (!hideImages) {
                        MarkdownImagePager(
                            images = item.images,
                            onImageClick = onImageClick,
                        )
                    }
            }
        }
    }
}

@Composable
private fun MarkdownIrBlockDisplay(
    display: MarkdownIrBlockDisplay,
    context: MarkdownIrRenderContext,
) {
    when (display) {
        is MarkdownIrBlockDisplay.Paragraph ->
            MarkdownIrParagraph(
                text = display.text,
                images = display.images,
                context = context,
            )
        is MarkdownIrBlockDisplay.Heading ->
            Text(
                text = display.text,
                style =
                    when (display.level) {
                        1u -> MaterialTheme.typography.headlineMedium
                        2u -> MaterialTheme.typography.headlineSmall
                        else -> MaterialTheme.typography.titleMedium
                    },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        is MarkdownIrBlockDisplay.BlockQuote ->
            Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp)) {
                display.blocks.forEach { child ->
                    MarkdownIrBlockDisplay(
                        display = child,
                        context = context,
                    )
                }
            }
        is MarkdownIrBlockDisplay.ListBlock ->
            Column(modifier = Modifier.fillMaxWidth()) {
                display.items.forEach { item ->
                    MarkdownIrListItem(
                        item = item,
                        context = context,
                    )
                }
            }
        is MarkdownIrBlockDisplay.CodeBlock ->
            Text(
                text = display.literal,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        is MarkdownIrBlockDisplay.ThematicBreak -> Text("────────", modifier = Modifier.fillMaxWidth())
        is MarkdownIrBlockDisplay.Table ->
            Column(modifier = Modifier.fillMaxWidth()) {
                display.rows.forEach { row ->
                    Text(row)
                }
            }
        is MarkdownIrBlockDisplay.HtmlBlock -> Text(display.literal, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun MarkdownIrListItem(
    item: MarkdownIrListItemDisplay,
    context: MarkdownIrRenderContext,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        val actionSpan = item.actionSpan
        val checked = item.checked
        val onTaskClick = context.onTaskClick
        if (actionSpan != null && checked != null) {
            Checkbox(
                checked = checked,
                onCheckedChange = if (onTaskClick == null) null else ({ onTaskClick(actionSpan) }),
            )
        } else {
            Text(item.marker, modifier = Modifier.width(28.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            val childContext =
                context.copy(
                    onTextTapFeedback = null,
                    onTextBodyClick = null,
                    onTextDoubleClick = null,
                    onTextLongClick = null,
                )
            item.blocks.forEach { child ->
                MarkdownIrBlockDisplay(
                    display = child,
                    context = childContext,
                )
            }
        }
    }
}

@Composable
private fun MarkdownIrParagraph(
    text: AnnotatedString,
    images: List<MarkdownPresentationImage>,
    context: MarkdownIrRenderContext,
) {
    if (text.isNotEmpty()) {
        MDText(
            text = text,
            style = null,
            enableTextSelection = context.enableTextSelection,
            selectionRegistrar = context.textSelectionRegistrar,
            onTapFeedback = context.onTextTapFeedback,
            onBodyClick = context.onTextBodyClick,
            onDoubleClick = context.onTextDoubleClick,
            onLongClick = context.onTextLongClick,
        )
    }
    if (!context.hideImages) {
        images.forEach { presentationImage ->
            val media = context.mediaPresentationResolver?.invoke(presentationImage)
            if (media != null && context.mediaContent != null) {
                context.mediaContent(media)
            } else if (presentationImage.mediaClass != MediaClass.AUDIO) {
                // Non-audio destinations enter the image request lane; MarkdownImageBlock
                // judges real image-ness fail-closed through the Coil load state.
                MarkdownImageBlock(presentationImage, context.onImageClick)
            }
        }
    }
}
