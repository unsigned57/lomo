package com.lomo.ui.component.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.lomo.ui.text.MemoParagraphText
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.ast.ASTNode

@Composable
internal fun rememberModernMarkdownComponents(): MarkdownComponents =
    remember {
        markdownComponents(
            paragraph = { model ->
                ModernMarkdownParagraph(model = model)
            },
            heading1 = { model ->
                ModernMarkdownHeading(model = model, style = model.typography.h1)
            },
            heading2 = { model ->
                ModernMarkdownHeading(model = model, style = model.typography.h2)
            },
            heading3 = { model ->
                ModernMarkdownHeading(model = model, style = model.typography.h3)
            },
            heading4 = { model ->
                ModernMarkdownHeading(model = model, style = model.typography.h4)
            },
            heading5 = { model ->
                ModernMarkdownHeading(model = model, style = model.typography.h5)
            },
            heading6 = { model ->
                ModernMarkdownHeading(model = model, style = model.typography.h6)
            },
            setextHeading1 = { model ->
                ModernMarkdownHeading(model = model, style = model.typography.h1)
            },
            setextHeading2 = { model ->
                ModernMarkdownHeading(model = model, style = model.typography.h2)
            },
        )
    }

@Composable
private fun ModernMarkdownParagraph(model: MarkdownComponentModel) {
    val style = model.typography.paragraph
    val annotatedText = remember(model.content, model.node, style) { model.buildAnnotatedText(style) }
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
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ModernMarkdownHeading(
    model: MarkdownComponentModel,
    style: TextStyle,
) {
    val annotatedText = remember(model.content, model.node, style) { model.buildAnnotatedText(style) }
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
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun buildModernMarkdownAnnotatedText(
    content: String,
    node: ASTNode,
    style: TextStyle,
    tokenSpec: ModernMarkdownTokenSpec,
): AnnotatedString =
    content.buildMarkdownAnnotatedString(
        textNode = node,
        style = style,
        annotatorSettings = buildModernAnnotatorSettings(tokenSpec),
    )

private fun MarkdownComponentModel.buildAnnotatedText(style: TextStyle) =
    buildModernMarkdownAnnotatedText(
        content = content,
        node = node,
        style = style,
        tokenSpec =
            ModernMarkdownTokenSpec(
                paragraphStyle = typography.paragraph,
                heading1Style = typography.h1,
                heading2Style = typography.h2,
                heading3Style = typography.h3,
                heading4Style = typography.h4,
                heading5Style = typography.h5,
                heading6Style = typography.h6,
                listStyle = typography.list,
                quoteStyle = typography.quote,
                codeStyle = typography.code,
                inlineCodeStyle = typography.inlineCode,
                tableStyle = typography.table,
                linkStyle = typography.textLink,
                blockSpacing = com.lomo.ui.theme.memoParagraphBlockSpacing(),
                listSpacing = com.lomo.ui.theme.memoParagraphBlockSpacing(),
                listItemSpacing = 4.dp,
            ),
    )

private fun buildModernAnnotatorSettings(tokenSpec: ModernMarkdownTokenSpec): AnnotatorSettings =
    DefaultAnnotatorSettings(
        linkTextSpanStyle = tokenSpec.linkStyle,
        codeSpanStyle = tokenSpec.inlineCodeStyle.toInlineSpanStyle(),
        annotator = markdownAnnotator(),
    )

private fun TextStyle.toInlineSpanStyle(): SpanStyle =
    SpanStyle(
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        background = background,
        textDecoration = textDecoration,
    )
