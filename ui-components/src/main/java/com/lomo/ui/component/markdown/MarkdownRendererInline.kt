package com.lomo.ui.component.markdown

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.lomo.ui.text.MemoDisplayTextState
import com.lomo.ui.text.MemoParagraphText
import com.lomo.ui.text.appendBoundarySpaceIfNeeded
import com.lomo.ui.text.appendNormalizedDisplayLiteral
import com.lomo.ui.theme.memoBodyTextStyle
import kotlinx.collections.immutable.ImmutableList
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text

@Composable
internal fun MDText(
    text: AnnotatedString,
    style: TextStyle?,
    enableTextSelection: Boolean = false,
) {
    if (text.isEmpty()) return

    val layoutSample: CharSequence = text
    val baseStyle = style ?: MaterialTheme.typography.memoBodyTextStyle()
    val finalStyle =
        resolveMarkdownParagraphTextStyle(
            baseStyle = baseStyle,
            fallbackColor = MaterialTheme.colorScheme.onSurface,
            text = layoutSample,
        )

    MemoParagraphText(
        text = text,
        style = finalStyle,
        modifier = Modifier.fillMaxWidth(),
        selectable = enableTextSelection,
    )
}

@Composable
internal fun MDImage(
    image: Image,
    onImageClick: ((String) -> Unit)? = null,
) {
    MarkdownImageBlock(
        image = image,
        onImageClick = onImageClick,
    )
}

@Composable
internal fun MDImageGallery(
    images: ImmutableList<Image>,
    onImageClick: ((String) -> Unit)? = null,
) {
    MarkdownImagePager(
        images = images,
        onImageClick = onImageClick,
    )
}

internal fun AnnotatedString.Builder.appendNode(
    node: Node,
    colorScheme: ColorScheme,
    displayState: MemoDisplayTextState = MemoDisplayTextState(),
) {
    when (node) {
        is Text -> appendNormalizedDisplayLiteral(node.literal.orEmpty(), displayState)

        is Code -> {
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = colorScheme.surfaceVariant,
                ),
            ) { append(node.literal) }
            displayState.previousVisibleChar = null
        }

        is Emphasis -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                visitChildren(node, colorScheme, displayState)
            }
        }

        is StrongEmphasis -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                visitChildren(node, colorScheme, displayState)
            }
        }

        is Strikethrough -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                visitChildren(node, colorScheme, displayState)
            }
        }

        is Link -> {
            pushLink(LinkAnnotation.Url(node.destination))
            withStyle(
                SpanStyle(
                    color = colorScheme.primary,
                ),
            ) { visitChildren(node, colorScheme, displayState) }
            pop()
        }

        is Image -> {
            append("[Image: ${node.title ?: "View"}]")
            displayState.previousVisibleChar = null
        }

        is SoftLineBreak,
        is HardLineBreak,
        -> {
            append("\n")
            displayState.previousVisibleChar = '\n'
        }

        else -> visitChildren(node, colorScheme, displayState)
    }
}

internal fun AnnotatedString.Builder.visitChildren(
    parent: Node,
    colorScheme: ColorScheme,
    displayState: MemoDisplayTextState = MemoDisplayTextState(),
) {
    var child = parent.firstChild
    while (child != null) {
        appendBoundarySpaceIfNeeded(
            nextVisibleChar = child.firstMixedScriptCharOrNull(),
            state = displayState,
        )
        appendNode(child, colorScheme, displayState)
        child = child.next
    }
}

internal data class VoiceMemoItem(
    val url: String,
)

internal data class ImageGalleryItem(
    val images: List<Image>,
)

internal fun Node.toImageOnlyParagraphOrNull(): Image? {
    if (this !is Paragraph) {
        return null
    }
    var imageNode: Image? = null
    var isImageOnlyParagraph = true
    var child = firstChild
    while (child != null) {
        when (child) {
            is Image -> {
                if (imageNode != null) {
                    isImageOnlyParagraph = false
                }
                imageNode = child
            }

            is SoftLineBreak,
            is HardLineBreak,
            -> Unit

            is Text -> {
                if (!child.literal.isNullOrBlank()) {
                    isImageOnlyParagraph = false
                }
            }

            else -> {
                isImageOnlyParagraph = false
            }
        }
        child = if (isImageOnlyParagraph) child.next else null
    }
    return if (isImageOnlyParagraph) imageNode else null
}

internal fun Node.firstMixedScriptCharOrNull(): Char? =
    when (this) {
        is Text -> literal?.firstOrNull { !it.isWhitespace() }
        is Emphasis,
        is StrongEmphasis,
        is Strikethrough,
        is Link,
        is Paragraph,
        -> {
            var child = firstChild
            while (child != null) {
                val nested = child.firstMixedScriptCharOrNull()
                if (nested != null) return nested
                child = child.next
            }
            null
        }

        else -> null
    }
