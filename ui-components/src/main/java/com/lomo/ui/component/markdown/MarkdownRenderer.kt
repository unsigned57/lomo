package com.lomo.ui.component.markdown

// ... existing imports ...
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.decode.DataSource
import coil3.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.*

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
) {
    val root = precomputedNode ?: remember(content) { MarkdownParser.parse(content) }

    val totalBlocks =
        remember(root) {
            var count = 0
            var blockNode = root.node.firstChild
            while (blockNode != null) {
                count++
                blockNode = blockNode.next
            }
            count
        }

    LaunchedEffect(totalBlocks) { onTotalBlocks?.invoke(totalBlocks) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var node = root.node.firstChild
        var childIndex = 0
        while (node != null && childIndex < maxVisibleBlocks) {
            MDBlock(ImmutableNode(node), onTodoClick = onTodoClick, todoOverrides = todoOverrides, onImageClick = onImageClick)
            node = node.next
            childIndex++
        }
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
) {
    when (val n = node.node) {
        is Heading -> MDHeading(ImmutableNode(n), modifier)
        is Paragraph -> MDParagraph(ImmutableNode(n), modifier, baseStyle, onImageClick)
        is FencedCodeBlock -> MDCodeBlock(ImmutableNode(n), modifier)
        is IndentedCodeBlock -> MDIndentedCodeBlock(ImmutableNode(n), modifier)
        is BlockQuote -> MDBlockQuote(ImmutableNode(n), modifier, onTodoClick, todoOverrides)
        is BulletList -> MDBulletList(ImmutableNode(n), modifier, onTodoClick, todoOverrides)
        is OrderedList -> MDOrderedList(ImmutableNode(n), modifier, onTodoClick, todoOverrides)
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
) {
    // If paragraph contains images, we render them as blocks interspersed with text.
    // Standard Markdown treats images as inline. We iterate children.

    val colorScheme = MaterialTheme.colorScheme
    val items =
        remember(paragraph, colorScheme) {
            val p = paragraph.node as Paragraph
            val result = mutableListOf<Any>()
            var currentTextBuilder = AnnotatedString.Builder()
            var hasText = false

            fun flush() {
                if (hasText) {
                    result.add(currentTextBuilder.toAnnotatedString())
                    currentTextBuilder = AnnotatedString.Builder()
                    hasText = false
                }
            }

            var nodeInside = p.firstChild
            while (nodeInside != null) {
                if (nodeInside is Image) {
                    flush()
                    val dest = nodeInside.destination
                    if (dest != null &&
                        (dest.endsWith(".m4a") || dest.endsWith(".mp3") || dest.endsWith(".aac") || dest.endsWith(".wav"))
                    ) {
                        result.add(VoiceMemoItem(dest))
                    } else {
                        result.add(nodeInside)
                    }
                } else {
                    currentTextBuilder.appendNode(nodeInside, colorScheme)
                    if (currentTextBuilder.length > 0) hasText = true
                }
                nodeInside = nodeInside.next
            }
            flush()
            result
        }

    if (items.isEmpty()) return

    Column(modifier = modifier) {
        items.forEach { item ->
            when (item) {
                is AnnotatedString -> {
                    MDText(item, baseStyle)
                }

                is Image -> {
                    MDImage(item, onImageClick)
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
        val finalStyle =
            (style ?: MaterialTheme.typography.bodyMedium).copy(
                color = style?.color ?: MaterialTheme.colorScheme.onSurface,
            )

        Text(text = text, style = finalStyle, modifier = Modifier.fillMaxWidth())
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

        Column(modifier = Modifier.padding(start = 8.dp)) {
            var child = quote.firstChild
            while (child != null) {
                MDBlock(
                    ImmutableNode(child),
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
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
) {
    val list = bulletListNode.node as BulletList
    Column(modifier = modifier.padding(start = 8.dp)) {
        var node = list.firstChild
        while (node != null) {
            if (node is ListItem) {
                MDListItem(
                    ImmutableNode(node),
                    bullet = "•",
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
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
) {
    val list = orderedListNode.node as OrderedList
    Column(modifier = modifier.padding(start = 8.dp)) {
        var node = list.firstChild

        @Suppress("DEPRECATION")
        var index = list.startNumber
        while (node != null) {
            if (node is ListItem) {
                MDListItem(
                    ImmutableNode(node),
                    bullet = "$index.",
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
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

    Row(modifier = modifier.padding(vertical = 2.dp)) {
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

        Column {
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
    val destination = image.destination
    val context = LocalContext.current
    // Fix: Remember the ImageRequest to prevent reloading on every recomposition.
    // The key is the destination URL.
    val model =
        remember(destination, context) {
            ImageRequest.Builder(context).data(destination).build()
        }

    val aspectRatio = ImageRatioCache.get(destination)
    val modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp)
            .let {
                if (aspectRatio != null) it.aspectRatio(aspectRatio) else it
            }.let {
                if (onImageClick != null) {
                    it.clickable { onImageClick(destination) }
                } else {
                    it
                }
            }

    val sharedTransitionScope = com.lomo.ui.util.LocalSharedTransitionScope.current
    val animatedVisibilityScope = com.lomo.ui.util.LocalAnimatedVisibilityScope.current

    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    val sharedModifier =
        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedElement(
                    rememberSharedContentState(key = destination),
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
        } else {
            Modifier
        }

    SubcomposeAsyncImage(
        model = model,
        contentDescription = image.title ?: "Image",
        modifier = modifier.then(sharedModifier),
        contentScale = ContentScale.FillWidth,
    ) {
        val state by painter.state.collectAsState()

        when (state) {
            is AsyncImagePainter.State.Loading -> {
                // Loading placeholder with indicator
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp),
                            ),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    )
                }
            }

            is AsyncImagePainter.State.Success -> {
                val successState = state as AsyncImagePainter.State.Success
                val size = successState.painter.intrinsicSize
                if (size.width > 0 && size.height > 0) {
                    ImageRatioCache.put(destination, size.width / size.height)
                }

                if (successState.result.dataSource != DataSource.MEMORY_CACHE) {
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { visible = true }

                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(animationSpec = tween(300)),
                        exit = fadeOut(),
                    ) {
                        Image(
                            painter = painter,
                            contentDescription = image.title ?: "Image",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Image(
                        painter = painter,
                        contentDescription = image.title ?: "Image",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            is AsyncImagePainter.State.Error -> {
                // Error state with visual indicator
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .background(
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp),
                            ),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Text(
                        text = "⚠ Image failed to load",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            else -> {
                // Empty/Initial state - show placeholder
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                RoundedCornerShape(8.dp),
                            ),
                )
            }
        }
    }
}

private object ImageRatioCache {
    private const val MAX_CACHE_SIZE = 200
    private val cache =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Float>(MAX_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, Float>): Boolean = size > MAX_CACHE_SIZE
            },
        )

    fun get(url: String): Float? = cache[url]

    fun put(
        url: String,
        ratio: Float,
    ) {
        cache[url] = ratio
    }
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
