package com.lomo.ui.component.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

internal sealed interface ModernParagraphItem {
    data class Text(
        val text: AnnotatedString,
    ) : ModernParagraphItem

    data class Image(
        val image: ModernMarkdownImage,
    ) : ModernParagraphItem

    data class Gallery(
        val images: List<ModernMarkdownImage>,
    ) : ModernParagraphItem

    data class VoiceMemo(
        val url: String,
    ) : ModernParagraphItem
}

internal fun buildModernParagraphItems(
    content: String,
    paragraphNode: ASTNode,
    tokenSpec: ModernMarkdownTokenSpec,
    textStyle: TextStyle,
): List<ModernParagraphItem> {
    val items = mutableListOf<ModernParagraphItem>()
    val galleryImages = mutableListOf<ModernMarkdownImage>()
    var textStartOffset: Int? = null
    var textEndOffset = 0

    fun flushText() {
        val start = textStartOffset ?: return
        val fragment = content.substring(start, textEndOffset)
        textStartOffset = null
        textEndOffset = 0
        if (fragment.isBlank()) return
        val annotatedText =
            buildModernMarkdownAnnotatedTextFromFragment(
                fragment = fragment,
                style = textStyle,
                tokenSpec = tokenSpec,
            )
        if (annotatedText.isNotEmpty()) {
            items += ModernParagraphItem.Text(annotatedText)
        }
    }

    fun flushGallery() {
        when (galleryImages.size) {
            0 -> Unit
            1 -> items += ModernParagraphItem.Image(galleryImages.first())
            else -> items += ModernParagraphItem.Gallery(galleryImages.toList())
        }
        galleryImages.clear()
    }

    fun extendText(node: ASTNode) {
        if (textStartOffset == null) {
            textStartOffset = node.startOffset
        }
        textEndOffset = node.endOffset
    }

    paragraphNode.children.forEach { child ->
        val image = child.extractModernImageOrNull(content)
        when {
            image != null && image.destination.isVoiceMemoPath() -> {
                flushText()
                flushGallery()
                items += ModernParagraphItem.VoiceMemo(image.destination)
            }

            image != null -> {
                flushText()
                galleryImages += image
            }

            child.type == MarkdownTokenTypes.EOL || child.type == MarkdownTokenTypes.HARD_LINE_BREAK -> {
                if (galleryImages.isEmpty()) {
                    extendText(child)
                }
            }

            galleryImages.isNotEmpty() && child.isBlankWhitespaceNode(content) -> Unit

            else -> {
                flushGallery()
                extendText(child)
            }
        }
    }

    flushGallery()
    flushText()
    return items
}

internal fun buildModernMarkdownAnnotatedTextFromFragment(
    fragment: String,
    style: TextStyle,
    tokenSpec: ModernMarkdownTokenSpec,
): AnnotatedString {
    val root = parseModernMarkdownDocument(fragment)
    val renderNode = root.children.firstOrNull { it.type != MarkdownTokenTypes.EOL } ?: root
    return buildModernMarkdownAnnotatedText(
        content = fragment,
        node = renderNode,
        style = style,
        tokenSpec = tokenSpec,
    )
}

internal fun ASTNode.isBlankWhitespaceNode(content: String): Boolean =
    (type == MarkdownTokenTypes.WHITE_SPACE || type == MarkdownTokenTypes.TEXT) &&
        extractNodeText(content).isBlank()

internal fun ModernMarkdownImage.toCommonMarkImage(): org.commonmark.node.Image =
    org.commonmark.node.Image(destination, title)

internal fun String.isVoiceMemoPath(): Boolean =
    endsWith(".m4a", ignoreCase = true) ||
        endsWith(".mp3", ignoreCase = true) ||
        endsWith(".aac", ignoreCase = true) ||
        endsWith(".wav", ignoreCase = true)
