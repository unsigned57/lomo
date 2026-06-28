package com.lomo.ui.component.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.lomo.ui.text.MemoParagraphText
import com.lomo.ui.text.MemoTextSelectionRegistrar
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.toImmutableList
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

@Composable
internal fun ModernMarkdownRenderPlanContent(
    plan: ModernMarkdownRenderPlan,
    modifier: Modifier = Modifier,
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    todoOverrides: ImmutableMap<Int, Boolean> = persistentHashMapOf(),
    onImageClick: ((String) -> Unit)? = null,
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
    enableTextSelection: Boolean = false,
    textSelectionRegistrar: MemoTextSelectionRegistrar? = null,
    onTextTapFeedback: (() -> Unit)? = null,
    onTextBodyClick: (() -> Unit)? = null,
    onTextDoubleClick: (() -> Unit)? = null,
    onTextLongClick: (() -> Unit)? = null,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)? = null,
) {
    val tokenSpec = rememberModernMarkdownTokenSpec()
    val totalItems = plan.items.size
    val needsProgressive = totalItems > PROGRESSIVE_RENDER_INITIAL_BATCH
    var visibleCount by remember(plan) {
        mutableIntStateOf(
            if (needsProgressive) PROGRESSIVE_RENDER_INITIAL_BATCH else totalItems,
        )
    }

    if (needsProgressive && visibleCount < totalItems) {
        LaunchedEffect(plan, visibleCount) {
            withFrameNanos { }
            visibleCount = (visibleCount + PROGRESSIVE_RENDER_INCREMENT).coerceAtMost(totalItems)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(tokenSpec.blockSpacing),
    ) {
        plan.items.asSequence().take(visibleCount).forEach { item ->
            when (item) {
                is ModernMarkdownRenderItem.Block -> {
                    ModernMarkdownBlock(
                        node = item.node,
                        semanticBlock = item.semanticBlock,
                        content = plan.content,
                        tokenSpec = tokenSpec,
                        onTodoClick = onTodoClick,
                        todoOverrides = todoOverrides,
                        onImageClick = onImageClick,
                        mediaPresentationResolver = mediaPresentationResolver,
                        mediaContent = mediaContent,
                        enableTextSelection = enableTextSelection,
                        textSelectionRegistrar = textSelectionRegistrar,
                        onTextTapFeedback = onTextTapFeedback,
                        onTextBodyClick = onTextBodyClick,
                        onTextDoubleClick = onTextDoubleClick,
                        onTextLongClick = onTextLongClick,
                        hideImages = hideImages,
                    )
                }

                is ModernMarkdownRenderItem.Gallery -> {
                    if (!hideImages) {
                        ModernMarkdownGalleryItem(
                            images = item.images.toImmutableList(),
                            onImageClick = onImageClick,
                            mediaPresentationResolver = mediaPresentationResolver,
                            mediaContent = mediaContent,
                        )
                    }
                }
            }
        }
    }
}

private const val PROGRESSIVE_RENDER_INITIAL_BATCH = 20
private const val PROGRESSIVE_RENDER_INCREMENT = 30
private val MODERN_MARKDOWN_BLOCK_QUOTE_CONTENT_GAP = 8.dp

@Composable
internal fun ModernMarkdownBlock(
    node: ASTNode,
    semanticBlock: MarkdownSemanticBlock?,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    enableTextSelection: Boolean,
    textSelectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)? = null,
    baseParagraphStyle: TextStyle? = null,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    resolveModernMarkdownHeadingStyle(node = node, tokenSpec = tokenSpec)?.let { headingStyle ->
        val headingBlock = semanticBlock as? MarkdownSemanticBlock.Heading
        ModernMarkdownHeading(
            semanticBlock = headingBlock,
            blockStartOffset = node.startOffset,
            style = headingStyle,
            tokenSpec = tokenSpec,
            enableTextSelection = enableTextSelection,
            selectionRegistrar = textSelectionRegistrar,
            onTextTapFeedback = onTextTapFeedback,
            onTextBodyClick = onTextBodyClick,
            onTextDoubleClick = onTextDoubleClick,
            onTextLongClick = onTextLongClick,
        )
        return
    }

    RenderModernMarkdownStandardBlock(
        node = node,
        semanticBlock = semanticBlock,
        content = content,
        tokenSpec = tokenSpec,
        onTodoClick = onTodoClick,
        todoOverrides = todoOverrides,
        onImageClick = onImageClick,
        mediaPresentationResolver = mediaPresentationResolver,
        mediaContent = mediaContent,
        enableTextSelection = enableTextSelection,
        textSelectionRegistrar = textSelectionRegistrar,
        onTextTapFeedback = onTextTapFeedback,
        onTextBodyClick = onTextBodyClick,
        onTextDoubleClick = onTextDoubleClick,
        onTextLongClick = onTextLongClick,
        baseParagraphStyle = baseParagraphStyle,
        hideImages = hideImages,
    )
}

@Composable
private fun RenderModernMarkdownStandardBlock(
    node: ASTNode,
    semanticBlock: MarkdownSemanticBlock?,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    enableTextSelection: Boolean,
    textSelectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
    baseParagraphStyle: TextStyle?,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    when (node.type) {
        MarkdownElementTypes.PARAGRAPH ->
            ModernMarkdownParagraph(
                semanticBlock = semanticBlock as MarkdownSemanticBlock.Paragraph,
                blockStartOffset = node.startOffset,
                tokenSpec = tokenSpec,
                textStyle = baseParagraphStyle ?: tokenSpec.paragraphStyle,
                onImageClick = onImageClick,
                mediaPresentationResolver = mediaPresentationResolver,
                mediaContent = mediaContent,
                enableTextSelection = enableTextSelection,
                selectionRegistrar = textSelectionRegistrar,
                onTextTapFeedback = onTextTapFeedback,
                onTextBodyClick = onTextBodyClick,
                onTextDoubleClick = onTextDoubleClick,
                onTextLongClick = onTextLongClick,
                hideImages = hideImages,
            )

        MarkdownElementTypes.CODE_FENCE ->
            ModernMarkdownCodeFence(
                node = node,
                content = content,
                tokenSpec = tokenSpec,
                enableTextSelection = enableTextSelection,
                selectionRegistrar = textSelectionRegistrar,
                onTextTapFeedback = onTextTapFeedback,
                onTextBodyClick = onTextBodyClick,
                onTextDoubleClick = onTextDoubleClick,
                onTextLongClick = onTextLongClick,
            )

        MarkdownElementTypes.CODE_BLOCK ->
            ModernMarkdownIndentedCodeBlock(
                node = node,
                content = content,
                tokenSpec = tokenSpec,
                enableTextSelection = enableTextSelection,
                selectionRegistrar = textSelectionRegistrar,
                onTextTapFeedback = onTextTapFeedback,
                onTextBodyClick = onTextBodyClick,
                onTextDoubleClick = onTextDoubleClick,
                onTextLongClick = onTextLongClick,
            )

        MarkdownElementTypes.BLOCK_QUOTE,
        MarkdownElementTypes.UNORDERED_LIST,
        MarkdownElementTypes.ORDERED_LIST,
        GFMElementTypes.TABLE ->
            RenderModernMarkdownStructuredBlock(
                node = node,
                semanticBlock = semanticBlock,
                content = content,
                tokenSpec = tokenSpec,
                onTodoClick = onTodoClick,
                todoOverrides = todoOverrides,
                onImageClick = onImageClick,
                mediaPresentationResolver = mediaPresentationResolver,
                mediaContent = mediaContent,
                enableTextSelection = enableTextSelection,
                textSelectionRegistrar = textSelectionRegistrar,
                onTextTapFeedback = onTextTapFeedback,
                onTextBodyClick = onTextBodyClick,
                onTextDoubleClick = onTextDoubleClick,
                onTextLongClick = onTextLongClick,
                hideImages = hideImages,
            )

        MarkdownTokenTypes.HORIZONTAL_RULE -> HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        else ->
            ModernMarkdownFallbackBlock(
                node = node,
                content = content,
                tokenSpec = tokenSpec,
                enableTextSelection = enableTextSelection,
                selectionRegistrar = textSelectionRegistrar,
                onTextTapFeedback = onTextTapFeedback,
                onTextBodyClick = onTextBodyClick,
                onTextDoubleClick = onTextDoubleClick,
                onTextLongClick = onTextLongClick,
            )
    }
}

@Composable
private fun RenderModernMarkdownStructuredBlock(
    node: ASTNode,
    semanticBlock: MarkdownSemanticBlock?,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    enableTextSelection: Boolean,
    textSelectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    when (node.type) {
        MarkdownElementTypes.BLOCK_QUOTE ->
            ModernMarkdownBlockQuote(
                node = node,
                semanticBlock = semanticBlock as? MarkdownSemanticBlock.BlockQuote,
                content = content,
                tokenSpec = tokenSpec,
                onTodoClick = onTodoClick,
                todoOverrides = todoOverrides,
                onImageClick = onImageClick,
                mediaPresentationResolver = mediaPresentationResolver,
                mediaContent = mediaContent,
                enableTextSelection = enableTextSelection,
                textSelectionRegistrar = textSelectionRegistrar,
                onTextTapFeedback = onTextTapFeedback,
                onTextBodyClick = onTextBodyClick,
                onTextDoubleClick = onTextDoubleClick,
                onTextLongClick = onTextLongClick,
                hideImages = hideImages,
            )

        MarkdownElementTypes.UNORDERED_LIST ->
            ModernMarkdownUnorderedList(
                node = node,
                semanticBlock = semanticBlock as? MarkdownSemanticBlock.ListBlock,
                content = content,
                tokenSpec = tokenSpec,
                onTodoClick = onTodoClick,
                todoOverrides = todoOverrides,
                onImageClick = onImageClick,
                mediaPresentationResolver = mediaPresentationResolver,
                mediaContent = mediaContent,
                enableTextSelection = enableTextSelection,
                textSelectionRegistrar = textSelectionRegistrar,
                onTextTapFeedback = onTextTapFeedback,
                onTextBodyClick = onTextBodyClick,
                onTextDoubleClick = onTextDoubleClick,
                onTextLongClick = onTextLongClick,
                hideImages = hideImages,
            )

        MarkdownElementTypes.ORDERED_LIST ->
            ModernMarkdownOrderedList(
                node = node,
                semanticBlock = semanticBlock as? MarkdownSemanticBlock.ListBlock,
                content = content,
                tokenSpec = tokenSpec,
                onTodoClick = onTodoClick,
                todoOverrides = todoOverrides,
                onImageClick = onImageClick,
                mediaPresentationResolver = mediaPresentationResolver,
                mediaContent = mediaContent,
                enableTextSelection = enableTextSelection,
                textSelectionRegistrar = textSelectionRegistrar,
                onTextTapFeedback = onTextTapFeedback,
                onTextBodyClick = onTextBodyClick,
                onTextDoubleClick = onTextDoubleClick,
                onTextLongClick = onTextLongClick,
                hideImages = hideImages,
            )

        else ->
            ModernMarkdownTable(
                node = node,
                semanticBlock = semanticBlock as? MarkdownSemanticBlock.Table,
                content = content,
                tokenSpec = tokenSpec,
                enableTextSelection = enableTextSelection,
                selectionRegistrar = textSelectionRegistrar,
                onTextTapFeedback = onTextTapFeedback,
                onTextBodyClick = onTextBodyClick,
                onTextDoubleClick = onTextDoubleClick,
                onTextLongClick = onTextLongClick,
            )
    }
}

@Composable
private fun ModernMarkdownHeading(
    semanticBlock: MarkdownSemanticBlock.Heading?,
    blockStartOffset: Int,
    style: TextStyle,
    tokenSpec: ModernMarkdownTokenSpec,
    enableTextSelection: Boolean,
    selectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
) {
    val annotatedText =
        remember(semanticBlock, style, tokenSpec) {
            buildModernMarkdownAnnotatedTextFromSemanticInlines(
                inlines = semanticBlock?.inlines.orEmpty(),
                tokenSpec = tokenSpec,
            )
        }
    val fallbackColor = MaterialTheme.colorScheme.onSurface
    val finalStyle =
        remember(style, annotatedText, fallbackColor) {
            resolveMarkdownParagraphTextStyle(
                baseStyle = style,
                fallbackColor = fallbackColor,
                text = annotatedText.text,
            )
        }
    MemoParagraphText(
        text = annotatedText,
        style = finalStyle,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = tokenSpec.blockSpacing / 2),
        selectable = enableTextSelection,
        blockKey = MarkdownBlockKey.heading(blockStartOffset),
        selectionRegistrar = selectionRegistrar,
        onTapFeedback = onTextTapFeedback,
        onBodyClick = onTextBodyClick,
        onDoubleClick = onTextDoubleClick,
        onLongClick = onTextLongClick,
    )
}

@Composable
private fun ModernMarkdownParagraph(
    semanticBlock: MarkdownSemanticBlock.Paragraph,
    blockStartOffset: Int,
    tokenSpec: ModernMarkdownTokenSpec,
    textStyle: TextStyle,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    enableTextSelection: Boolean,
    selectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    val effectiveMediaPresentationResolver = mediaPresentationResolver.takeIf { mediaContent != null }
    val items =
        remember(semanticBlock, tokenSpec, textStyle, effectiveMediaPresentationResolver) {
            buildModernParagraphItems(
                paragraph = semanticBlock,
                tokenSpec = tokenSpec,
                mediaPresentationResolver = effectiveMediaPresentationResolver,
            )
        }
    if (items.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            when (item) {
                is ModernParagraphItem.Text -> {
                    MDText(
                        text = item.text,
                        style = textStyle,
                        enableTextSelection = enableTextSelection,
                        blockKey = MarkdownBlockKey.paragraphItem(blockStartOffset, index),
                        selectionRegistrar = selectionRegistrar,
                        onTapFeedback = onTextTapFeedback,
                        onBodyClick = onTextBodyClick,
                        onDoubleClick = onTextDoubleClick,
                        onLongClick = onTextLongClick,
                    )
                }

                is ModernParagraphItem.Image -> {
                    if (!hideImages) {
                        MDImage(
                            image = item.image,
                            onImageClick = onImageClick,
                        )
                    }
                }

                is ModernParagraphItem.Gallery -> {
                    if (!hideImages) {
                        MDImageGallery(
                            images = item.images.toImmutableList(),
                            onImageClick = onImageClick,
                        )
                    }
                }

                is ModernParagraphItem.Media -> {
                    if (!hideImages) {
                        mediaContent?.invoke(item.presentation)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernMarkdownCodeFence(
    node: ASTNode,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    enableTextSelection: Boolean,
    selectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
) {
    val code = remember(content, node) { node.extractCodeFenceContent(content) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(4.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        MemoParagraphText(
            text = code,
            style = tokenSpec.codeStyle.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(8.dp),
            selectable = enableTextSelection,
            blockKey = MarkdownBlockKey.codeFence(node.startOffset),
            selectionRegistrar = selectionRegistrar,
            onTapFeedback = onTextTapFeedback,
            onBodyClick = onTextBodyClick,
            onDoubleClick = onTextDoubleClick,
            onLongClick = onTextLongClick,
        )
    }
}

@Composable
private fun ModernMarkdownIndentedCodeBlock(
    node: ASTNode,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    enableTextSelection: Boolean,
    selectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
) {
    val code = remember(content, node) { node.extractIndentedCodeContent(content) }
    MemoParagraphText(
        text = code,
        style = tokenSpec.codeStyle.copy(fontFamily = FontFamily.Monospace),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
        selectable = enableTextSelection,
        blockKey = MarkdownBlockKey.codeBlock(node.startOffset),
        selectionRegistrar = selectionRegistrar,
        onTapFeedback = onTextTapFeedback,
        onBodyClick = onTextBodyClick,
        onDoubleClick = onTextDoubleClick,
        onLongClick = onTextLongClick,
    )
}

@Composable
private fun ModernMarkdownTable(
    node: ASTNode,
    semanticBlock: MarkdownSemanticBlock.Table?,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    enableTextSelection: Boolean,
    selectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
) {
    val rows = remember(semanticBlock, content, node) {
        semanticBlock?.toModernMarkdownTableRows() ?: node.extractModernMarkdownTableRows(content).toSemanticTableRows()
    }
    if (rows.isEmpty()) return

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(4.dp),
                ),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (rowIndex == 0) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
            ) {
                row.cells.forEachIndexed { cellIndex, cell ->
                    val annotatedText =
                        remember(cell, tokenSpec) {
                            buildModernMarkdownAnnotatedTextFromSemanticInlines(
                                inlines = cell.inlines,
                                tokenSpec = tokenSpec,
                            )
                        }
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        MDText(
                            text = annotatedText,
                            style =
                                if (rowIndex == 0) {
                                    tokenSpec.tableStyle.copy(fontWeight = FontWeight.Bold)
                                } else {
                                    tokenSpec.tableStyle
                                },
                            enableTextSelection = enableTextSelection,
                            blockKey = MarkdownBlockKey.tableCell(node.startOffset, rowIndex, cellIndex),
                            selectionRegistrar = selectionRegistrar,
                            onTapFeedback = onTextTapFeedback,
                            onBodyClick = onTextBodyClick,
                            onDoubleClick = onTextDoubleClick,
                            onLongClick = onTextLongClick,
                        )
                    }
                }
            }
            if (rowIndex != rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ModernMarkdownBlockQuote(
    node: ASTNode,
    semanticBlock: MarkdownSemanticBlock.BlockQuote?,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    enableTextSelection: Boolean,
    textSelectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    val indicatorStyle = resolveModernMarkdownQuoteIndicatorStyle(MaterialTheme.colorScheme)
    Layout(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        content = {
            Box(
                modifier =
                    Modifier
                        .background(
                            indicatorStyle.color,
                            RoundedCornerShape(indicatorStyle.cornerRadius),
                        ),
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(tokenSpec.blockSpacing),
            ) {
                node.children
                    .filter(::isRenderableNestedBlock)
                    .forEachIndexed { index, child ->
                        ModernMarkdownBlock(
                            node = child,
                            semanticBlock = semanticBlock?.blocks?.getOrNull(index),
                            content = content,
                            tokenSpec = tokenSpec,
                            onTodoClick = onTodoClick,
                            todoOverrides = todoOverrides,
                            onImageClick = onImageClick,
                            mediaPresentationResolver = mediaPresentationResolver,
                            mediaContent = mediaContent,
                            enableTextSelection = enableTextSelection,
                            textSelectionRegistrar = textSelectionRegistrar,
                            onTextTapFeedback = onTextTapFeedback,
                            onTextBodyClick = onTextBodyClick,
                            onTextDoubleClick = onTextDoubleClick,
                            onTextLongClick = onTextLongClick,
                            baseParagraphStyle =
                                resolveModernMarkdownBlockTextStyle(
                                    node = node,
                                    tokenSpec = tokenSpec,
                                    baseParagraphStyle = tokenSpec.paragraphStyle,
                                ),
                            hideImages = hideImages,
                        )
                    }
            }
        },
        measurePolicy = { measurables, constraints ->
            val indicatorWidth = indicatorStyle.thickness.roundToPx()
            val contentGap = MODERN_MARKDOWN_BLOCK_QUOTE_CONTENT_GAP.roundToPx()
            val contentWidth =
                if (constraints.hasBoundedWidth) {
                    (constraints.maxWidth - indicatorWidth - contentGap).coerceAtLeast(0)
                } else {
                    constraints.maxWidth
                }
            val contentConstraints =
                constraints.copy(
                    minWidth = 0,
                    maxWidth = contentWidth,
                )
            val contentPlaceable = measurables[1].measure(contentConstraints)
            val preferredWidth = indicatorWidth + contentGap + contentPlaceable.width
            val layoutWidth = preferredWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
            val layoutHeight =
                contentPlaceable.height.coerceIn(
                    constraints.minHeight,
                    constraints.maxHeight,
                )
            val indicatorPlaceable =
                measurables[0].measure(
                    Constraints.fixed(
                        width = indicatorWidth,
                        height = layoutHeight,
                    ),
                )
            layout(layoutWidth, layoutHeight) {
                indicatorPlaceable.placeRelative(0, 0)
                contentPlaceable.placeRelative(indicatorWidth + contentGap, 0)
            }
        },
    )
}

@Composable
private fun ModernMarkdownFallbackBlock(
    node: ASTNode,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    enableTextSelection: Boolean,
    selectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
) {
    val fragment = content.substring(node.startOffset, node.endOffset)
    if (fragment.isBlank()) return
    val annotatedText =
        remember(fragment, tokenSpec) {
            buildModernMarkdownAnnotatedTextFromFragment(
                fragment = fragment,
                style = tokenSpec.paragraphStyle,
                tokenSpec = tokenSpec,
            )
        }
    if (annotatedText.isEmpty()) return
    MDText(
        text = annotatedText,
        style = tokenSpec.paragraphStyle,
        enableTextSelection = enableTextSelection,
        blockKey = MarkdownBlockKey.fallback(node.startOffset),
        selectionRegistrar = selectionRegistrar,
        onTapFeedback = onTextTapFeedback,
        onBodyClick = onTextBodyClick,
        onDoubleClick = onTextDoubleClick,
        onLongClick = onTextLongClick,
    )
}

internal data class ModernMarkdownTableRow(
    val cells: List<String>,
)
