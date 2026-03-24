package com.lomo.ui.component.markdown

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.text.scriptAwareTextAlign
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
) {
    if (text.isEmpty()) return

    val plainText = text.isPlainTextContent()
    val displayText = if (plainText) text.text.normalizeCjkMixedSpacingForDisplay() else null
    val layoutSample: CharSequence = displayText ?: text
    val baseStyle = style ?: MaterialTheme.typography.bodyMedium
    val finalStyle =
        baseStyle
            .copy(color = style?.color ?: MaterialTheme.colorScheme.onSurface)
            .scriptAwareFor(layoutSample)
    val textAlign = layoutSample.scriptAwareTextAlign()

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
    images: List<Image>,
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
) {
    when (node) {
        is Text -> append(node.literal)

        is Code -> {
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = colorScheme.surfaceVariant,
                ),
            ) { append(node.literal) }
        }

        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { visitChildren(node, colorScheme) }

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

        is Image -> append("[Image: ${node.title ?: "View"}]")

        is SoftLineBreak,
        is HardLineBreak,
        -> append("\n")

        else -> visitChildren(node, colorScheme)
    }
}

internal fun AnnotatedString.Builder.visitChildren(
    parent: Node,
    colorScheme: ColorScheme,
) {
    var child = parent.firstChild
    while (child != null) {
        appendNode(child, colorScheme)
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

private fun AnnotatedString.isPlainTextContent(): Boolean = spanStyles.isEmpty() && paragraphStyles.isEmpty()
