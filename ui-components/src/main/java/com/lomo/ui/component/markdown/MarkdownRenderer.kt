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
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.text.scriptAwareTextAlign
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
    enableTextSelection: Boolean = false,
) {
    val root by
        produceState<ImmutableNode?>(
            initialValue = precomputedNode,
            key1 = content,
            key2 = precomputedNode,
        ) {
            value = precomputedNode ?: withContext(Dispatchers.Default) { MarkdownParser.parse(content) }
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
    val totalBlocks =
        remember(root, onTotalBlocks != null) {
            if (onTotalBlocks == null) {
                null
            } else {
                var count = 0
                var blockNode = root?.node?.firstChild
                while (blockNode != null) {
                    count++
                    blockNode = blockNode.next
                }
                count
            }
        }

    LaunchedEffect(totalBlocks) {
        totalBlocks?.let { latestOnTotalBlocks?.invoke(it) }
    }

    val renderedContent: @Composable () -> Unit = {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var node = root?.node?.firstChild
            var childIndex = 0
            while (node != null && childIndex < maxVisibleBlocks) {
                val firstImage = node.toImageOnlyParagraphOrNull()
                if (firstImage != null) {
                    val galleryImages = mutableListOf<Image>()
                    var cursor: Node? = node
                    while (cursor != null) {
                        val image = cursor.toImageOnlyParagraphOrNull() ?: break
                        galleryImages.add(image)
                        cursor = cursor.next
                    }
                    if (galleryImages.size > 1) {
                        MDImageGallery(galleryImages, onImageClick)
                        node = cursor
                        childIndex++
                        continue
                    }
                }

                MDBlock(
                    ImmutableNode(node),
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    onImageClick = onImageClick,
                    enableTextSelection = enableTextSelection,
                )
                node = node.next
                childIndex++
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
    val textStyle = MaterialTheme.typography.bodyLarge.scriptAwareFor(normalizedContent)

    val fallbackContent: @Composable () -> Unit = {
        Text(
            text = normalizedContent,
            style = textStyle,
            textAlign = normalizedContent.scriptAwareTextAlign(),
            modifier = modifier,
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
private fun MDBlock(
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
            1 -> {
                MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            }

            2 -> {
                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            }

            3 -> {
                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            }

            4 -> {
                MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            }

            5 -> {
                MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
            }

            6 -> {
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
    val text = remember(heading) { buildAnnotatedString { appendNode(node, colorScheme) } }

    Text(text = text, style = style, modifier = modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun MDParagraph(
    paragraph: ImmutableNode,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle? = null,
    onImageClick: ((String) -> Unit)? = null,
    enableTextSelection: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    val items =
        remember(paragraph, colorScheme) {
            val p = paragraph.node as Paragraph
            val result = mutableListOf<Any>()
            var currentTextBuilder = AnnotatedString.Builder()
            var hasText = false
            val currentGalleryImages = mutableListOf<Image>()

            fun flushText() {
                if (hasText) {
                    result.add(currentTextBuilder.toAnnotatedString())
                    currentTextBuilder = AnnotatedString.Builder()
                    hasText = false
                }
            }

            fun flushGallery() {
                if (currentGalleryImages.isEmpty()) return
                if (currentGalleryImages.size == 1) {
                    result.add(currentGalleryImages.first())
                } else {
                    result.add(ImageGalleryItem(currentGalleryImages.toList()))
                }
                currentGalleryImages.clear()
            }

            var nodeInside = p.firstChild
            while (nodeInside != null) {
                if (nodeInside is Image) {
                    val dest = nodeInside.destination
                    if (dest != null &&
                        (dest.endsWith(".m4a") || dest.endsWith(".mp3") || dest.endsWith(".aac") || dest.endsWith(".wav"))
                    ) {
                        flushText()
                        flushGallery()
                        result.add(VoiceMemoItem(dest))
                    } else {
                        flushText()
                        currentGalleryImages.add(nodeInside)
                    }
                } else if (nodeInside is SoftLineBreak || nodeInside is HardLineBreak) {
                    if (currentGalleryImages.isEmpty()) {
                        currentTextBuilder.appendNode(nodeInside, colorScheme)
                        if (currentTextBuilder.length > 0) hasText = true
                    }
                } else if (nodeInside is Text && nodeInside.literal?.isBlank() == true && currentGalleryImages.isNotEmpty()) {
                    // Ignore pure whitespace separators between adjacent image nodes.
                } else {
                    flushGallery()
                    currentTextBuilder.appendNode(nodeInside, colorScheme)
                    if (currentTextBuilder.length > 0) hasText = true
                }
                nodeInside = nodeInside.next
            }
            flushGallery()
            flushText()
            result
        }

    if (items.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        items.forEach { item ->
            when (item) {
                is AnnotatedString -> {
                    MDText(item, baseStyle)
                }

                is Image -> {
                    MDImage(item, onImageClick)
                }

                is ImageGalleryItem -> {
                    MDImageGallery(item.images, onImageClick)
                }

                is VoiceMemoItem -> {
                    com.lomo.ui.component.media
                        .AudioPlayerCard(relativeFilePath = item.url)
                }
            }
        }
    }
}

@Composable
private fun MDText(
    text: AnnotatedString,
    style: TextStyle?,
) {
    if (text.isNotEmpty()) {
        val plainText = text.isPlainTextContent()
        val displayText = if (plainText) text.text.normalizeCjkMixedSpacingForDisplay() else null
        val layoutSample: CharSequence = displayText ?: text
        val baseStyle = style ?: MaterialTheme.typography.bodyMedium
        val finalStyle = baseStyle.copy(color = style?.color ?: MaterialTheme.colorScheme.onSurface).scriptAwareFor(layoutSample)
        val textAlign = layoutSample.scriptAwareTextAlign()
        val textContent: @Composable () -> Unit = {
            if (displayText != null) {
                Text(
                    text = displayText,
                    style = finalStyle,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = text,
                    style = finalStyle,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        textContent()
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

        Column(modifier = Modifier.padding(start = 8.dp).fillMaxWidth()) {
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
    Column(modifier = modifier.fillMaxWidth().padding(start = 8.dp)) {
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
    Column(modifier = modifier.fillMaxWidth().padding(start = 8.dp)) {
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
    val taskMarker = listItem.firstChild as? TaskListItemMarker
    val isTask = taskMarker != null
    val sourceLine = listItem.sourceSpans.firstOrNull()?.lineIndex
    // Use override if available, otherwise use parsed state
    val parsedChecked = taskMarker?.isChecked == true
    val effectiveChecked =
        if (sourceLine != null && todoOverrides.containsKey(sourceLine)) {
            todoOverrides[sourceLine] ?: parsedChecked
        } else {
            parsedChecked
        }

    // Determine style based on checked state
    val itemStyle =
        if (effectiveChecked) {
            MaterialTheme.typography.bodyMedium.copy(
                textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            MaterialTheme.typography.bodyMedium
        }

    Row(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (isTask) {
            val appHaptic = com.lomo.ui.util.LocalAppHapticFeedback.current

            // Scale animation for checkbox interaction feedback
            val scale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (effectiveChecked) 1.0f else 0.92f,
                animationSpec =
                    androidx.compose.animation.core.spring(
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                    ),
                label = "checkbox_scale",
            )

            Checkbox(
                checked = effectiveChecked,
                onCheckedChange = { checked ->
                    appHaptic.medium()
                    if (sourceLine != null) {
                        onTodoClick?.invoke(sourceLine, checked)
                    }
                },
                modifier =
                    Modifier
                        .size(24.dp)
                        .padding(end = 8.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
            )
        } else {
            if (bullet == "•") {
                Box(
                    modifier =
                        Modifier
                            .width(24.dp)
                            .height(24.dp) // Align with line height
                            .padding(end = 4.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Canvas(modifier = Modifier.size(6.dp)) { drawCircle(color = dotColor) }
                }
            } else {
                Text(
                    text = bullet,
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    modifier = Modifier.width(20.dp).padding(end = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            var node = listItem.firstChild
            while (node != null) {
                if (node is TaskListItemMarker) {
                    node = node.next
                    continue
                }
                MDBlock(
                    ImmutableNode(node),
                    baseStyle = itemStyle,
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    enableTextSelection = enableTextSelection,
                )
                node = node.next
            }
        }
    }
}

@Composable
private fun MDImage(
    image: Image,
    onImageClick: ((String) -> Unit)? = null,
) {
    MarkdownImageBlock(
        image = image,
        onImageClick = onImageClick,
    )
}

@Composable
private fun MDImageGallery(
    images: List<Image>,
    onImageClick: ((String) -> Unit)? = null,
) {
    MarkdownImagePager(
        images = images,
        onImageClick = onImageClick,
    )
}

// Helper to build AnnotatedString from inline nodes
private fun AnnotatedString.Builder.appendNode(
    node: Node,
    colorScheme: ColorScheme,
) {
    when (node) {
        is Text -> {
            append(node.literal)
        }

        is Code -> {
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = colorScheme.surfaceVariant,
                ),
            ) { append(node.literal) }
        }

        is Emphasis -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { visitChildren(node, colorScheme) }
        }

        is StrongEmphasis -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { visitChildren(node, colorScheme) }
        }

        is Strikethrough -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                visitChildren(node, colorScheme)
            }
        }

        is Link -> {
            pushLink(LinkAnnotation.Url(node.destination))
            withStyle(
                SpanStyle(
                    color = colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            ) { visitChildren(node, colorScheme) }
            pop()
        }

        is Image -> {
            // Inline image in text context (e.g. inside link).
            append("[Image: ${node.title ?: "View"}]")
        }

        is SoftLineBreak -> {
            append("\n")
        }

        is HardLineBreak -> {
            append("\n")
        }

        else -> {
            visitChildren(node, colorScheme)
        }
    }
}

private fun AnnotatedString.Builder.visitChildren(
    parent: Node,
    colorScheme: ColorScheme,
) {
    var child = parent.firstChild
    while (child != null) {
        appendNode(child, colorScheme)
        child = child.next
    }
}

private data class VoiceMemoItem(
    val url: String,
)

private data class ImageGalleryItem(
    val images: List<Image>,
)

private fun AnnotatedString.isPlainTextContent(): Boolean = spanStyles.isEmpty() && paragraphStyles.isEmpty()

private fun Node.toImageOnlyParagraphOrNull(): Image? {
    if (this !is Paragraph) return null
    var imageNode: Image? = null
    var child = firstChild
    while (child != null) {
        when (child) {
            is Image -> {
                if (imageNode != null) return null
                imageNode = child
            }

            is SoftLineBreak,
            is HardLineBreak,
            -> {
                Unit
            }

            is Text -> {
                if (!child.literal.isNullOrBlank()) {
                    return null
                }
            }

            else -> {
                return null
            }
        }
        child = child.next
    }
    return imageNode
}
