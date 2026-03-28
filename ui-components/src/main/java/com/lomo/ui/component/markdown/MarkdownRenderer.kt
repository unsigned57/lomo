package com.lomo.ui.component.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay
import com.lomo.ui.text.MemoParagraphText
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.text.scriptAwareTextAlign
import com.lomo.ui.theme.memoBodyTextStyle
import com.lomo.ui.theme.memoParagraphBlockSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak

@Composable
fun MarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    maxVisibleBlocks: Int = Int.MAX_VALUE,
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    todoOverrides: Map<Int, Boolean> = emptyMap(), // State overlay for checkboxes
    onImageClick: ((String) -> Unit)? = null,
    onTotalBlocks: ((Int) -> Unit)? = null,
    precomputedNode: ImmutableNode? = null,
    knownTagsToStrip: List<String> = emptyList(),
    enableTextSelection: Boolean = false,
) {
    if (
        shouldUseModernMarkdownBackend(
            maxVisibleBlocks = maxVisibleBlocks,
            hasTodoToggleHandler = onTodoClick != null,
            hasTodoOverrides = todoOverrides.isNotEmpty(),
            hasKnownTagsToStrip = knownTagsToStrip.isNotEmpty(),
            hasImageClickHandler = onImageClick != null,
            hasPrecomputedNode = precomputedNode != null,
        )
    ) {
        ModernMarkdownRenderer(
            content = content,
            modifier = modifier,
            maxVisibleBlocks = maxVisibleBlocks,
            onTodoClick = onTodoClick,
            todoOverrides = todoOverrides,
            onImageClick = onImageClick,
            onTotalBlocks = onTotalBlocks,
            knownTagsToStrip = knownTagsToStrip,
            enableTextSelection = enableTextSelection,
        )
        return
    }

    val root by
        produceState<ImmutableNode?>(
            initialValue = precomputedNode,
            key1 = content,
            key2 = precomputedNode,
            key3 = knownTagsToStrip,
        ) {
            value =
                precomputedNode
                    ?: withContext(Dispatchers.Default) {
                        MarkdownKnownTagFilter.eraseKnownTags(MarkdownParser.parse(content), knownTagsToStrip)
                    }
        }

    if (root == null) {
        MarkdownRendererFallback(
            content = content,
            modifier = modifier,
            enableTextSelection = enableTextSelection,
        )
        return
    }

    val latestOnTotalBlocks by rememberUpdatedState(onTotalBlocks)
    val totalBlocks = remember(root, onTotalBlocks != null) { countMarkdownBlocks(root, onTotalBlocks != null) }
    val renderedItems = remember(root, maxVisibleBlocks) { buildMarkdownRenderItems(root, maxVisibleBlocks) }

    LaunchedEffect(totalBlocks) {
        totalBlocks?.let { latestOnTotalBlocks?.invoke(it) }
    }

    val renderedContent: @Composable () -> Unit = {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(memoParagraphBlockSpacing()),
        ) {
            renderedItems.forEach { item ->
                when (item) {
                    is MarkdownRenderItem.Block -> {
                        MDBlock(
                            node = item.node,
                            onTodoClick = onTodoClick,
                            todoOverrides = todoOverrides,
                            onImageClick = onImageClick,
                            enableTextSelection = enableTextSelection,
                        )
                    }

                    is MarkdownRenderItem.Gallery -> MDImageGallery(item.images, onImageClick)
                }
            }
        }
    }

    if (enableTextSelection) {
        SelectionContainer {
            renderedContent()
        }
    } else {
        renderedContent()
    }
}

@Composable
private fun MarkdownRendererFallback(
    content: String,
    modifier: Modifier = Modifier,
    enableTextSelection: Boolean = false,
) {
    val normalizedContent = remember(content) { content.normalizeCjkMixedSpacingForDisplay() }
    val textStyle =
        MaterialTheme.typography.memoBodyTextStyle()
            .copy(color = MaterialTheme.colorScheme.onSurface)
            .scriptAwareFor(normalizedContent)

    val fallbackContent: @Composable () -> Unit = {
        MemoParagraphText(
            text = normalizedContent,
            style = textStyle,
            modifier = modifier,
            selectable = enableTextSelection,
        )
    }

    if (enableTextSelection) {
        SelectionContainer {
            fallbackContent()
        }
    } else {
        fallbackContent()
    }
}

@Composable
internal fun MDBlock(
    node: ImmutableNode,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle? = null,
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    todoOverrides: Map<Int, Boolean> = emptyMap(),
    onImageClick: ((String) -> Unit)? = null,
    enableTextSelection: Boolean = false,
) {
    when (val n = node.node) {
        is Heading -> MDHeading(ImmutableNode(n), modifier)
        is Paragraph -> MDParagraph(ImmutableNode(n), modifier, baseStyle, onImageClick, enableTextSelection)
        is FencedCodeBlock -> MDCodeBlock(ImmutableNode(n), modifier)
        is IndentedCodeBlock -> MDIndentedCodeBlock(ImmutableNode(n), modifier)
        is BlockQuote -> MDBlockQuote(ImmutableNode(n), modifier, onTodoClick, todoOverrides, enableTextSelection)
        is BulletList -> MDBulletList(ImmutableNode(n), modifier, onTodoClick, todoOverrides, enableTextSelection)
        is OrderedList -> MDOrderedList(ImmutableNode(n), modifier, onTodoClick, todoOverrides, enableTextSelection)
        is ThematicBreak -> HorizontalDivider(modifier = modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun MDHeading(
    heading: ImmutableNode,
    modifier: Modifier = Modifier,
) {
    val node = heading.node as Heading
    val style =
        when (node.level) {
            HEADING_LEVEL_1 -> {
                MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            }

            HEADING_LEVEL_2 -> {
                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            }

            HEADING_LEVEL_3 -> {
                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            }

            HEADING_LEVEL_4 -> {
                MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            }

            HEADING_LEVEL_5 -> {
                MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
            }

            HEADING_LEVEL_6 -> {
                MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                MaterialTheme.typography.bodyLarge
            }
        }

    val colorScheme = MaterialTheme.colorScheme
    val text = remember(heading, colorScheme) { buildAnnotatedString { appendNode(node, colorScheme) } }
    val finalStyle = remember(style, text) { style.scriptAwareFor(text) }

    Text(
        text = text,
        style = finalStyle,
        textAlign = text.scriptAwareTextAlign(),
        modifier = modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

private const val HEADING_LEVEL_1 = 1
private const val HEADING_LEVEL_2 = 2
private const val HEADING_LEVEL_3 = 3
private const val HEADING_LEVEL_4 = 4
private const val HEADING_LEVEL_5 = 5
private const val HEADING_LEVEL_6 = 6

@Composable
private fun MDParagraph(
    paragraph: ImmutableNode,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle? = null,
    onImageClick: ((String) -> Unit)? = null,
    enableTextSelection: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    val items = remember(paragraph, colorScheme) { buildParagraphItems(paragraph, colorScheme) }

    if (items.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        items.forEach { item ->
            ParagraphItemContent(
                item = item,
                baseStyle = baseStyle,
                onImageClick = onImageClick,
                enableTextSelection = enableTextSelection,
            )
        }
    }
}

@Composable
private fun MDCodeBlock(
    block: ImmutableNode,
    modifier: Modifier = Modifier,
) {
    val node = block.node as FencedCodeBlock
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            text = node.literal ?: "",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
private fun MDIndentedCodeBlock(
    block: ImmutableNode,
    modifier: Modifier = Modifier,
) {
    val node = block.node as IndentedCodeBlock
    // Render indented code blocks as regular text with indentation.
    // This correctly handles content like poems or lists that might have accidental 4-space indent
    // which Markdown interprets as code, but users usually intend as indentation.
    Text(
        text = node.literal ?: "",
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun MDBlockQuote(
    blockQuoteNode: ImmutableNode,
    modifier: Modifier = Modifier,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: Map<Int, Boolean> = emptyMap(),
    enableTextSelection: Boolean = false,
) {
    val quote = blockQuoteNode.node as BlockQuote
    Row(modifier = modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Box(
            modifier =
                Modifier
                    .width(4.dp)
                    .background(
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(2.dp),
                    ).height(IntrinsicSize.Min),
        )

        Column(
            modifier = Modifier.padding(start = 8.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(memoParagraphBlockSpacing()),
        ) {
            var child = quote.firstChild
            while (child != null) {
                MDBlock(
                    ImmutableNode(child),
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    enableTextSelection = enableTextSelection,
                )
                child = child.next
            }
        }
    }
}

@Composable
private fun MDBulletList(
    bulletListNode: ImmutableNode,
    modifier: Modifier = Modifier,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: Map<Int, Boolean> = emptyMap(),
    enableTextSelection: Boolean = false,
) {
    val list = bulletListNode.node as BulletList
    Column(
        modifier = modifier.fillMaxWidth().padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(memoParagraphBlockSpacing()),
    ) {
        var node = list.firstChild
        while (node != null) {
            if (node is ListItem) {
                MDListItem(
                    ImmutableNode(node),
                    bullet = "•",
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    enableTextSelection = enableTextSelection,
                )
            }
            node = node.next
        }
    }
}

@Composable
private fun MDOrderedList(
    orderedListNode: ImmutableNode,
    modifier: Modifier = Modifier,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: Map<Int, Boolean> = emptyMap(),
    enableTextSelection: Boolean = false,
) {
    val list = orderedListNode.node as OrderedList
    Column(
        modifier = modifier.fillMaxWidth().padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(memoParagraphBlockSpacing()),
    ) {
        var node = list.firstChild

        var index = list.markerStartNumber ?: 0
        while (node != null) {
            if (node is ListItem) {
                MDListItem(
                    ImmutableNode(node),
                    bullet = "$index.",
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    enableTextSelection = enableTextSelection,
                )
                index++
            }
            node = node.next
        }
    }
}

@Composable
private fun MDListItem(
    listItemNode: ImmutableNode,
    bullet: String,
    modifier: Modifier = Modifier,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: Map<Int, Boolean> = emptyMap(),
    enableTextSelection: Boolean = false,
) {
    val listItem = listItemNode.node as ListItem
    val presentation = rememberListItemPresentation(listItem, todoOverrides)

    Row(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        MDListItemLeading(
            bullet = bullet,
            presentation = presentation,
            onTodoClick = onTodoClick,
        )
        MDListItemContent(
            modifier = Modifier.weight(1f),
            listItem = listItem,
            itemStyle = presentation.itemStyle,
            onTodoClick = onTodoClick,
            todoOverrides = todoOverrides,
            enableTextSelection = enableTextSelection,
        )
    }
}
