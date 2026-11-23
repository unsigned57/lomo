package com.lomo.ui.component.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.lomo.domain.model.MediaClass
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.text.MemoTextSelectionRegistrar
import com.lomo.ui.theme.memoBodyTextStyle
import com.lomo.ui.theme.memoParagraphBlockSpacing

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
    imageContent: (@Composable (MarkdownPresentationImage) -> Unit)? = null,
    softWrapCode: Boolean = false,
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
        imageContent = imageContent,
        softWrapCode = softWrapCode,
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
    imageContent: (@Composable (MarkdownPresentationImage) -> Unit)? = null,
    softWrapCode: Boolean = false,
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
            imageContent = imageContent,
            softWrapCode = softWrapCode,
        )
    val blockSpacing = memoParagraphBlockSpacing()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(blockSpacing),
    ) {
        plan.items.forEach { item ->
            when (item) {
                is MarkdownIrPresentationItem.Block ->
                    MarkdownIrBlockDisplay(
                        display = item.display,
                        context = context,
                        blockSpacing = blockSpacing,
                    )

                is MarkdownIrPresentationItem.Gallery ->
                    if (!hideImages) {
                        val customImageContent = context.imageContent
                        if (customImageContent != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item.images.forEach { image -> customImageContent(image) }
                            }
                        } else {
                            MarkdownImagePager(
                                images = item.images,
                                onImageClick = onImageClick,
                            )
                        }
                    }
            }
        }
    }
}

private const val CHECKBOX_INTERACTIVE_SIZE_DP = 28

@Composable
private fun MarkdownIrBlockDisplay(
    display: MarkdownIrBlockDisplay,
    context: MarkdownIrRenderContext,
    blockSpacing: Dp,
) {
    val nestedSpacing = (blockSpacing * 0.55f).coerceAtLeast(4.dp)
    when (display) {
        is MarkdownIrBlockDisplay.Paragraph ->
            MarkdownIrParagraph(
                text = display.text,
                mediaGroups = display.mediaGroups,
                context = context,
            )

        is MarkdownIrBlockDisplay.Heading -> MarkdownIrHeading(display = display, context = context)

        is MarkdownIrBlockDisplay.BlockQuote ->
            MarkdownIrBlockQuote(
                blocks = display.blocks,
                context = context,
                nestedSpacing = nestedSpacing,
            )

        is MarkdownIrBlockDisplay.ListBlock ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(nestedSpacing),
            ) {
                display.items.forEach { item ->
                    MarkdownIrListItem(
                        item = item,
                        ordered = display.ordered,
                        context = context,
                        itemSpacing = nestedSpacing,
                    )
                }
            }

        is MarkdownIrBlockDisplay.CodeBlock ->
            MarkdownCodeBlock(
                language = display.language,
                literal = display.literal,
                softWrap = context.softWrapCode,
            )

        is MarkdownIrBlockDisplay.ThematicBreak ->
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

        is MarkdownIrBlockDisplay.Table ->
            MarkdownTableBlock(
                alignments = display.alignments,
                header = display.header,
                rows = display.rows,
            )

        is MarkdownIrBlockDisplay.HtmlBlock -> MarkdownIrHtmlBlock(literal = display.literal)
    }
}

@Composable
private fun MarkdownIrHeading(display: MarkdownIrBlockDisplay.Heading, context: MarkdownIrRenderContext) {
    val headingColor =
        context.contentColorOverride
            ?: if (MarkdownRenderTokens.isMutedHeadingLevel(display.level)) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            }
    val headingStyle = headingTextStyle(display.level)
    Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        MDText(
            text = display.text,
            style = headingStyle,
            contentColor = headingColor,
            enableTextSelection = context.enableTextSelection,
            selectionRegistrar = context.textSelectionRegistrar,
            onTapFeedback = context.onTextTapFeedback,
            onBodyClick = context.onTextBodyClick,
            onDoubleClick = context.onTextDoubleClick,
            onLongClick = context.onTextLongClick,
        )
    }
}

@Composable
private fun headingTextStyle(level: UInt): TextStyle {
    val bodyStyle = MaterialTheme.typography.memoBodyTextStyle()
    val scale = MarkdownRenderTokens.headingScale(level)
    return bodyStyle.copy(
        fontSize =
        if (bodyStyle.fontSize.isSpecified) bodyStyle.fontSize * scale.fontScale else bodyStyle.fontSize,
        lineHeight =
        if (bodyStyle.lineHeight.isSpecified) bodyStyle.lineHeight * scale.lineScale else bodyStyle.lineHeight,
        fontWeight = scale.weight,
    )
}

@Composable
private fun MarkdownIrBlockQuote(
    blocks: List<MarkdownIrBlockDisplay>,
    context: MarkdownIrRenderContext,
    nestedSpacing: Dp,
) {
    val colorScheme = MaterialTheme.colorScheme
    val barColor = MarkdownRenderTokens.quoteAccentBarColor(colorScheme)
    val quoteContext =
        context.copy(
            contentColorOverride = colorScheme.onSurfaceVariant,
        )
    Column(
        modifier =
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MarkdownRenderTokens.quoteContainerColor(colorScheme))
            .drawBehind {
                val barWidthPx = 3.dp.toPx()
                drawRect(
                    color = barColor,
                    topLeft = Offset.Zero,
                    size = Size(width = barWidthPx, height = size.height),
                )
            }
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(nestedSpacing),
    ) {
        blocks.forEach { child ->
            MarkdownIrBlockDisplay(
                display = child,
                context = quoteContext,
                blockSpacing = nestedSpacing,
            )
        }
    }
}

@Composable
private fun MarkdownIrHtmlBlock(literal: String) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MarkdownRenderTokens.htmlBlockContainerColor(colorScheme),
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Text(
            text = literal,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun MarkdownIrListItem(
    item: MarkdownIrListItemDisplay,
    ordered: Boolean,
    context: MarkdownIrRenderContext,
    itemSpacing: Dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        val actionSpan = item.actionSpan
        val checked = item.checked
        val onTaskClick = context.onTaskClick
        if (actionSpan != null && checked != null) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides CHECKBOX_INTERACTIVE_SIZE_DP.dp) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = if (onTaskClick == null) null else ({ onTaskClick(actionSpan) }),
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        } else {
            val markerModifier =
                if (ordered) {
                    Modifier.widthIn(min = 24.dp).padding(end = 4.dp)
                } else {
                    Modifier.widthIn(min = 16.dp).padding(end = 6.dp)
                }
            Text(
                item.marker,
                style = MaterialTheme.typography.memoBodyTextStyle(),
                color = context.contentColorOverride ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = markerModifier,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            val childContext =
                context.copy(
                    contentColorOverride =
                    if (checked == true) MaterialTheme.colorScheme.onSurfaceVariant else context.contentColorOverride,
                    onTextTapFeedback = null,
                    onTextBodyClick = null,
                    onTextDoubleClick = null,
                    onTextLongClick = null,
                )
            item.blocks.forEach { child ->
                MarkdownIrBlockDisplay(
                    display = child,
                    context = childContext,
                    blockSpacing = itemSpacing,
                )
            }
        }
    }
}

@Composable
private fun MarkdownIrParagraph(
    text: AnnotatedString,
    mediaGroups: List<kotlinx.collections.immutable.ImmutableList<MarkdownPresentationImage>>,
    context: MarkdownIrRenderContext,
) {
    if (text.isNotEmpty()) {
        MDText(
            text = text,
            style = null,
            enableTextSelection = context.enableTextSelection,
            contentColor = context.contentColorOverride,
            selectionRegistrar = context.textSelectionRegistrar,
            onTapFeedback = context.onTextTapFeedback,
            onBodyClick = context.onTextBodyClick,
            onDoubleClick = context.onTextDoubleClick,
            onLongClick = context.onTextLongClick,
        )
    }
    if (!context.hideImages) {
        val customImageContent = context.imageContent
        mediaGroups.forEach { group ->
            if (group.size > 1 && customImageContent == null) {
                MarkdownImagePager(group, context.onImageClick)
                return@forEach
            }
            group.forEach { presentationImage ->
                val media = context.mediaPresentationResolver?.invoke(presentationImage)
                if (media != null && context.mediaContent != null) {
                    context.mediaContent(media)
                } else if (presentationImage.mediaClass != MediaClass.AUDIO) {
                    if (customImageContent != null) {
                        customImageContent(presentationImage)
                    } else {
                        // Non-audio destinations enter the image request lane; MarkdownImageBlock
                        // judges real image-ness fail-closed through the Coil load state.
                        MarkdownImageBlock(presentationImage, context.onImageClick)
                    }
                }
            }
        }
    }
}
