package com.lomo.ui.component.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Image
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text

internal sealed interface MarkdownRenderItem {
    data class Block(
        val node: ImmutableNode,
    ) : MarkdownRenderItem

    data class Gallery(
        val images: List<Image>,
    ) : MarkdownRenderItem
}

internal fun countMarkdownBlocks(
    root: ImmutableNode?,
    shouldCount: Boolean,
): Int? {
    if (!shouldCount) return null

    var count = 0
    var blockNode = root?.node?.firstChild
    while (blockNode != null) {
        count++
        blockNode = blockNode.next
    }
    return count
}

internal fun buildMarkdownRenderItems(
    root: ImmutableNode?,
    maxVisibleBlocks: Int,
): List<MarkdownRenderItem> {
    val items = mutableListOf<MarkdownRenderItem>()
    var node = root?.node?.firstChild
    var childIndex = 0

    while (node != null && childIndex < maxVisibleBlocks) {
        val gallery = consumeImageGallery(node)
        if (gallery != null) {
            items += MarkdownRenderItem.Gallery(gallery.images)
            node = gallery.nextNode
        } else {
            items += MarkdownRenderItem.Block(ImmutableNode(node))
            node = node.next
        }
        childIndex++
    }

    return items
}

private data class ImageGallerySequence(
    val images: List<Image>,
    val nextNode: Node?,
)

private fun consumeImageGallery(node: Node): ImageGallerySequence? {
    if (node.toImageOnlyParagraphOrNull() == null) return null

    val galleryImages = mutableListOf<Image>()
    var cursor: Node? = node
    while (cursor != null) {
        val image = cursor.toImageOnlyParagraphOrNull() ?: break
        galleryImages += image
        cursor = cursor.next
    }

    return if (galleryImages.size > 1) ImageGallerySequence(galleryImages, cursor) else null
}

internal sealed interface ParagraphItem

private data class ParagraphText(
    val text: AnnotatedString,
) : ParagraphItem

private data class ParagraphImage(
    val image: Image,
) : ParagraphItem

private data class ParagraphGallery(
    val images: List<Image>,
) : ParagraphItem

private data class ParagraphVoiceMemo(
    val url: String,
) : ParagraphItem

internal fun buildParagraphItems(
    paragraph: ImmutableNode,
    colorScheme: ColorScheme,
): List<ParagraphItem> {
    val accumulator = ParagraphAccumulator(colorScheme)
    var node = (paragraph.node as Paragraph).firstChild

    while (node != null) {
        accumulator.process(node)
        node = node.next
    }

    return accumulator.build()
}

private class ParagraphAccumulator(
    private val colorScheme: ColorScheme,
) {
    private val items = mutableListOf<ParagraphItem>()
    private val galleryImages = mutableListOf<Image>()
    private var currentTextBuilder = AnnotatedString.Builder()

    fun process(node: Node) {
        when {
            node is Image && node.isVoiceMemo() -> addVoiceMemo(node.destination)
            node is Image -> addImage(node)
            node is SoftLineBreak || node is HardLineBreak -> appendLineBreak(node)
            shouldIgnoreWhitespaceNode(node) -> Unit
            else -> appendInlineNode(node)
        }
    }

    fun build(): List<ParagraphItem> {
        flushGallery()
        flushText()
        return items
    }

    private fun addVoiceMemo(url: String) {
        flushText()
        flushGallery()
        items += ParagraphVoiceMemo(url)
    }

    private fun addImage(image: Image) {
        flushText()
        galleryImages += image
    }

    private fun appendLineBreak(node: Node) {
        if (galleryImages.isEmpty()) {
            currentTextBuilder.appendNode(node, colorScheme)
        }
    }

    private fun appendInlineNode(node: Node) {
        flushGallery()
        currentTextBuilder.appendNode(node, colorScheme)
    }

    private fun flushText() {
        if (currentTextBuilder.length > 0) {
            items += ParagraphText(currentTextBuilder.toAnnotatedString())
            currentTextBuilder = AnnotatedString.Builder()
        }
    }

    private fun flushGallery() {
        when (galleryImages.size) {
            0 -> Unit
            1 -> items += ParagraphImage(galleryImages.first())
            else -> items += ParagraphGallery(galleryImages.toList())
        }
        galleryImages.clear()
    }

    private fun shouldIgnoreWhitespaceNode(node: Node): Boolean =
        node is Text && node.literal?.isBlank() == true && galleryImages.isNotEmpty()
}

private fun Image.isVoiceMemo(): Boolean {
    val destination = destination ?: return false
    return listOf(".m4a", ".mp3", ".aac", ".wav").any(destination::endsWith)
}

@Composable
internal fun ParagraphItemContent(
    item: ParagraphItem,
    baseStyle: TextStyle?,
    onImageClick: ((String) -> Unit)?,
) {
    when (item) {
        is ParagraphText -> MDText(item.text, baseStyle)
        is ParagraphImage -> MDImage(item.image, onImageClick)
        is ParagraphGallery -> MDImageGallery(item.images, onImageClick)
        is ParagraphVoiceMemo -> com.lomo.ui.component.media.AudioPlayerCard(relativeFilePath = item.url)
    }
}

internal data class ListItemPresentation(
    val isTask: Boolean,
    val sourceLine: Int?,
    val effectiveChecked: Boolean,
    val itemStyle: TextStyle,
)

@Composable
internal fun rememberListItemPresentation(
    listItem: ListItem,
    todoOverrides: Map<Int, Boolean>,
): ListItemPresentation {
    val checkedItemStyle =
        MaterialTheme.typography.bodyMedium.copy(
            textDecoration = TextDecoration.LineThrough,
            color = MaterialTheme.colorScheme.outline,
        )
    val normalItemStyle = MaterialTheme.typography.bodyMedium
    return remember(listItem, todoOverrides, checkedItemStyle, normalItemStyle) {
        val taskMarker = listItem.firstChild as? TaskListItemMarker
        val sourceLine = listItem.sourceSpans.firstOrNull()?.lineIndex
        val parsedChecked = taskMarker?.isChecked == true
        val effectiveChecked =
            if (sourceLine != null && todoOverrides.containsKey(sourceLine)) {
                todoOverrides[sourceLine] ?: parsedChecked
            } else {
                parsedChecked
            }
        ListItemPresentation(
            isTask = taskMarker != null,
            sourceLine = sourceLine,
            effectiveChecked = effectiveChecked,
            itemStyle = if (effectiveChecked) checkedItemStyle else normalItemStyle,
        )
    }
}

@Composable
internal fun MDListItemLeading(
    bullet: String,
    presentation: ListItemPresentation,
    onTodoClick: ((Int, Boolean) -> Unit)?,
) {
    if (presentation.isTask) {
        val appHaptic = com.lomo.ui.util.LocalAppHapticFeedback.current
        val scale by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (presentation.effectiveChecked) 1.0f else 0.92f,
            animationSpec =
                androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                ),
            label = "checkbox_scale",
        )
        Checkbox(
            checked = presentation.effectiveChecked,
            onCheckedChange = { checked ->
                appHaptic.medium()
                presentation.sourceLine?.let { sourceLine -> onTodoClick?.invoke(sourceLine, checked) }
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
        MDBulletLeading(bullet)
    }
}

@Composable
private fun MDBulletLeading(bullet: String) {
    if (bullet == "•") {
        Box(
            modifier =
                Modifier
                    .width(24.dp)
                    .height(24.dp)
                    .padding(end = 4.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(modifier = Modifier.size(6.dp)) { drawCircle(color = dotColor) }
        }
    } else {
        Text(
            text = bullet,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.width(20.dp).padding(end = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun MDListItemContent(
    modifier: Modifier = Modifier,
    listItem: ListItem,
    itemStyle: TextStyle,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: Map<Int, Boolean>,
    enableTextSelection: Boolean,
) {
    Column(modifier = modifier) {
        var node = listItem.firstChild
        while (node != null) {
            if (node !is TaskListItemMarker) {
                MDBlock(
                    ImmutableNode(node),
                    baseStyle = itemStyle,
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    enableTextSelection = enableTextSelection,
                )
            }
            node = node.next
        }
    }
}
