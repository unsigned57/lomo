package com.lomo.ui.component.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.intellij.markdown.MarkdownTokenTypes

data class MarkdownMediaPresentation(
    val source: String,
    val description: String? = null,
    val kind: String,
)

typealias MarkdownMediaPresentationResolver = (ModernMarkdownImage) -> MarkdownMediaPresentation?

data class MarkdownMediaPresentationAdapter(
    val resolver: MarkdownMediaPresentationResolver,
    val content: @Composable (MarkdownMediaPresentation) -> Unit,
)

internal sealed interface ModernParagraphItem {
    data class Text(
        val text: AnnotatedString,
    ) : ModernParagraphItem

    data class Image(
        val image: ModernMarkdownImage,
    ) : ModernParagraphItem

    data class Gallery(
        val images: ImmutableList<ModernMarkdownImage>,
    ) : ModernParagraphItem

    data class Media(
        val presentation: MarkdownMediaPresentation,
    ) : ModernParagraphItem
}

internal fun buildModernParagraphItems(
    paragraph: MarkdownSemanticBlock.Paragraph,
    tokenSpec: ModernMarkdownTokenSpec,
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
): List<ModernParagraphItem> {
    val items = mutableListOf<ModernParagraphItem>()
    val galleryImages = mutableListOf<ModernMarkdownImage>()
    val textInlines = mutableListOf<MarkdownSemanticInline>()

    fun flushText() {
        if (textInlines.isEmpty()) return
        val annotatedText =
            buildModernMarkdownAnnotatedTextFromSemanticInlines(
                inlines = textInlines.toList(),
                tokenSpec = tokenSpec,
            )
        textInlines.clear()
        if (annotatedText.isNotEmpty()) {
            items += ModernParagraphItem.Text(annotatedText)
        }
    }

    fun flushGallery() {
        when (galleryImages.size) {
            0 -> Unit
            1 -> items += ModernParagraphItem.Image(galleryImages.first())
            else -> items += ModernParagraphItem.Gallery(galleryImages.toImmutableList())
        }
        galleryImages.clear()
    }

    paragraph.inlines.forEach { inline ->
        val image = inline.toModernMarkdownImageOrNull()
        val mediaPresentation = image?.let { mediaPresentationResolver?.invoke(it) }
        when {
            mediaPresentation != null -> {
                flushText()
                flushGallery()
                items += ModernParagraphItem.Media(mediaPresentation)
            }

            image != null -> {
                flushText()
                galleryImages += image
            }

            else -> {
                flushGallery()
                textInlines += inline
            }
        }
    }

    flushGallery()
    flushText()
    return items
}

internal fun buildModernMarkdownAnnotatedTextFromSemanticInlines(
    inlines: List<MarkdownSemanticInline>,
    tokenSpec: ModernMarkdownTokenSpec,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val state = SemanticInlineAnnotatedTextState(builder = builder, tokenSpec = tokenSpec)
    inlines.forEach { inline -> state.append(inline) }
    return builder.toAnnotatedString()
}

private class SemanticInlineAnnotatedTextState(
    val builder: AnnotatedString.Builder,
    val tokenSpec: ModernMarkdownTokenSpec,
) {
    private val activeHtmlStyles = mutableListOf<SemanticHtmlStyle>()

    fun append(inline: MarkdownSemanticInline) {
        when (inline) {
            is MarkdownSemanticInline.Text -> appendText(inline.plainText)
            is MarkdownSemanticInline.Code -> appendStyledText(
                text = inline.plainText,
                style = tokenSpec.inlineCodeStyle.toInlineSpanStyle(),
            )
            is MarkdownSemanticInline.Strong -> appendStyledChildren(
                inlines = inline.inlines,
                style = SpanStyle(fontWeight = FontWeight.Bold),
            )
            is MarkdownSemanticInline.Emphasis -> appendStyledChildren(
                inlines = inline.inlines,
                style = SpanStyle(fontStyle = FontStyle.Italic),
            )
            is MarkdownSemanticInline.Strikethrough -> appendStyledChildren(
                inlines = inline.inlines,
                style = SpanStyle(textDecoration = TextDecoration.LineThrough),
            )
            is MarkdownSemanticInline.Link -> appendLink(inline)
            is MarkdownSemanticInline.Highlight -> appendStyledChildren(
                inlines = inline.inlines,
                style = tokenSpec.highlightSpanStyle,
            )
            is MarkdownSemanticInline.Image -> appendText(inline.altText)
            MarkdownSemanticInline.SoftBreak,
            MarkdownSemanticInline.HardBreak,
            -> appendText("\n")
            is MarkdownSemanticInline.HtmlInline -> appendHtml(inline.plainText)
        }
    }

    private fun appendText(text: String) {
        if (text.isEmpty()) return
        val start = builder.length
        builder.append(text)
        if (start < builder.length) {
            activeHtmlStyles.forEach { style ->
                builder.addStyle(style.toSpanStyle(tokenSpec), start, builder.length)
            }
        }
    }

    private fun appendStyledText(
        text: String,
        style: SpanStyle,
    ) {
        val start = builder.length
        appendText(text)
        if (start < builder.length) {
            builder.addStyle(style, start, builder.length)
        }
    }

    private fun appendStyledChildren(
        inlines: List<MarkdownSemanticInline>,
        style: SpanStyle,
    ) {
        val start = builder.length
        inlines.forEach(::append)
        if (start < builder.length) {
            builder.addStyle(style, start, builder.length)
        }
    }

    @OptIn(ExperimentalTextApi::class)
    private fun appendLink(inline: MarkdownSemanticInline.Link) {
        val start = builder.length
        inline.inlines.forEach(::append)
        if (start >= builder.length) return
        val linkStyle = tokenSpec.linkStyle.style ?: SpanStyle()
        builder.addStyle(linkStyle, start, builder.length)
        if (inline.destination.isNotBlank()) {
            builder.addLink(
                LinkAnnotation.Url(
                    url = inline.destination,
                    styles = tokenSpec.linkStyle,
                ),
                start,
                builder.length,
            )
        }
    }

    private fun appendHtml(literal: String) {
        when (val tag = literal.toSemanticHtmlTag()) {
            SemanticHtmlTag.LineBreak -> appendText("\n")
            is SemanticHtmlTag.OpenStyle -> activeHtmlStyles += tag.style
            is SemanticHtmlTag.CloseStyle -> activeHtmlStyles.removeLastMatching(tag.style)
            null -> appendText(literal)
        }
    }
}

private sealed interface SemanticHtmlTag {
    data object LineBreak : SemanticHtmlTag

    data class OpenStyle(
        val style: SemanticHtmlStyle,
    ) : SemanticHtmlTag

    data class CloseStyle(
        val style: SemanticHtmlStyle,
    ) : SemanticHtmlTag
}

private enum class SemanticHtmlStyle {
    Bold,
    Italic,
    Strikethrough,
    InlineCode,
    Underline,
}

private fun SemanticHtmlStyle.toSpanStyle(tokenSpec: ModernMarkdownTokenSpec): SpanStyle =
    when (this) {
        SemanticHtmlStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
        SemanticHtmlStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
        SemanticHtmlStyle.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        SemanticHtmlStyle.InlineCode -> tokenSpec.inlineCodeStyle.toInlineSpanStyle()
        SemanticHtmlStyle.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
    }

private fun String.toSemanticHtmlTag(): SemanticHtmlTag? {
    val match = SEMANTIC_HTML_TAG_PATTERN.matchEntire(trim()) ?: return null
    val isClosing = match.groupValues[1] == "/"
    val tagName = match.groupValues[2].lowercase()
    if (tagName == "br") return SemanticHtmlTag.LineBreak
    val style =
        when (tagName) {
            "b", "strong" -> SemanticHtmlStyle.Bold
            "i", "em" -> SemanticHtmlStyle.Italic
            "s", "strike", "del" -> SemanticHtmlStyle.Strikethrough
            "code" -> SemanticHtmlStyle.InlineCode
            "u", "ins" -> SemanticHtmlStyle.Underline
            else -> return null
        }
    return if (isClosing) {
        SemanticHtmlTag.CloseStyle(style)
    } else {
        SemanticHtmlTag.OpenStyle(style)
    }
}

private fun MutableList<SemanticHtmlStyle>.removeLastMatching(style: SemanticHtmlStyle) {
    val index = indexOfLast { it == style }
    if (index >= 0) {
        removeAt(index)
    }
}

private fun MarkdownSemanticInline.toModernMarkdownImageOrNull(): ModernMarkdownImage? =
    (this as? MarkdownSemanticInline.Image)
        ?.takeIf { it.destination.isNotBlank() }
        ?.let { image ->
            ModernMarkdownImage(
                destination = image.destination,
                title = image.altText.takeIf(String::isNotBlank),
            )
        }

private val SEMANTIC_HTML_TAG_PATTERN = Regex("""<\s*(/?)\s*([A-Za-z][A-Za-z0-9:-]*)(?:\s+[^>]*)?/?>""")

internal fun buildModernMarkdownAnnotatedTextFromFragment(
    fragment: String,
    style: TextStyle,
    tokenSpec: ModernMarkdownTokenSpec,
): AnnotatedString {
    val normalizedFragment = normalizeModernParagraphFragmentForDisplay(fragment)
    val extensionReadyFragment = preprocessModernMarkdownInlineExtensions(normalizedFragment)
    val root = parseModernMarkdownDocument(extensionReadyFragment)
    val renderNode = root.children.firstOrNull { it.type != MarkdownTokenTypes.EOL } ?: root
    return buildModernMarkdownAnnotatedText(
        content = extensionReadyFragment,
        node = renderNode,
        style = style,
        tokenSpec = tokenSpec,
    )
}

internal fun normalizeModernParagraphFragmentForDisplay(
    fragment: String,
): String {
    if (!fragment.contains('\n') && !fragment.contains('\r')) {
        return fragment
    }

    val normalizedLineEndings =
        fragment
            .replace("\r\n", "\n")
            .replace('\r', '\n')

    if (!normalizedLineEndings.contains('\n')) {
        return normalizedLineEndings
    }

    val result = StringBuilder(normalizedLineEndings.length + MARKDOWN_VISIBLE_LINE_BREAK_BUFFER_PADDING)
    normalizedLineEndings.forEachIndexed { index, char ->
        if (char != '\n') {
            result.append(char)
            return@forEachIndexed
        }

        val adjacentNewline =
            normalizedLineEndings.getOrNull(index - 1) == '\n' ||
                normalizedLineEndings.getOrNull(index + 1) == '\n'
        val alreadyHardBreak =
            result.lastOrNull() == '\\' ||
                result.trailingSpacesCount() >= MARKDOWN_HARD_BREAK_SPACE_COUNT

        if (adjacentNewline || alreadyHardBreak) {
            result.append('\n')
        } else {
            result.append(MARKDOWN_VISIBLE_LINE_BREAK)
        }
    }
    return result.toString()
}

private fun StringBuilder.trailingSpacesCount(): Int {
    var count = 0
    var index = length - 1
    while (index >= 0 && this[index] == ' ') {
        count++
        index--
    }
    return count
}

private const val MARKDOWN_HARD_BREAK_SPACE_COUNT = 2
private const val MARKDOWN_VISIBLE_LINE_BREAK = "  \n"
private const val MARKDOWN_VISIBLE_LINE_BREAK_BUFFER_PADDING = 8
