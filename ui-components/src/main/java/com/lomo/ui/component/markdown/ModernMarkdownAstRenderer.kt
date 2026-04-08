package com.lomo.ui.component.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lomo.ui.text.MemoParagraphText
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.toImmutableList
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

@Composable
internal fun ModernMarkdownRenderPlanContent(
    plan: ModernMarkdownRenderPlan,
    modifier: Modifier = Modifier,
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    todoOverrides: ImmutableMap<Int, Boolean> = persistentHashMapOf(),
    onImageClick: ((String) -> Unit)? = null,
    enableTextSelection: Boolean = false,
) {
    val tokenSpec = rememberModernMarkdownTokenSpec()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(tokenSpec.blockSpacing),
    ) {
        plan.items.forEach { item ->
            when (item) {
                is ModernMarkdownRenderItem.Block -> {
                    ModernMarkdownBlock(
                        node = item.node,
                        content = plan.content,
                        tokenSpec = tokenSpec,
                        onTodoClick = onTodoClick,
                        todoOverrides = todoOverrides,
                        onImageClick = onImageClick,
                        enableTextSelection = enableTextSelection,
                    )
                }

                is ModernMarkdownRenderItem.Gallery -> {
                    MDImageGallery(
                        images = item.images.map(ModernMarkdownImage::toCommonMarkImage).toImmutableList(),
                        onImageClick = onImageClick,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ModernMarkdownBlock(
    node: ASTNode,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    enableTextSelection: Boolean,
    baseParagraphStyle: TextStyle? = null,
) {
    resolveModernMarkdownHeadingStyle(node = node, tokenSpec = tokenSpec)?.let { headingStyle ->
        ModernMarkdownHeading(
            node = node,
            content = content,
            style = headingStyle,
            tokenSpec = tokenSpec,
            enableTextSelection = enableTextSelection,
        )
        return
    }

    when (node.type) {
        MarkdownElementTypes.PARAGRAPH -> {
            ModernMarkdownParagraph(
                node = node,
                content = content,
                tokenSpec = tokenSpec,
                textStyle = baseParagraphStyle ?: tokenSpec.paragraphStyle,
                onImageClick = onImageClick,
                enableTextSelection = enableTextSelection,
            )
        }

        MarkdownElementTypes.CODE_FENCE -> ModernMarkdownCodeFence(
            node = node,
            content = content,
            tokenSpec = tokenSpec,
            enableTextSelection = enableTextSelection,
        )

        MarkdownElementTypes.CODE_BLOCK -> ModernMarkdownIndentedCodeBlock(
            node = node,
            content = content,
            tokenSpec = tokenSpec,
            enableTextSelection = enableTextSelection,
        )

        MarkdownElementTypes.BLOCK_QUOTE -> {
            ModernMarkdownBlockQuote(
                node = node,
                content = content,
                tokenSpec = tokenSpec,
                onTodoClick = onTodoClick,
                todoOverrides = todoOverrides,
                onImageClick = onImageClick,
                enableTextSelection = enableTextSelection,
            )
        }

        MarkdownElementTypes.UNORDERED_LIST -> {
            ModernMarkdownUnorderedList(
                node = node,
                content = content,
                tokenSpec = tokenSpec,
                onTodoClick = onTodoClick,
                todoOverrides = todoOverrides,
                onImageClick = onImageClick,
                enableTextSelection = enableTextSelection,
            )
        }

        MarkdownElementTypes.ORDERED_LIST -> {
            ModernMarkdownOrderedList(
                node = node,
                content = content,
                tokenSpec = tokenSpec,
                onTodoClick = onTodoClick,
                todoOverrides = todoOverrides,
                onImageClick = onImageClick,
                enableTextSelection = enableTextSelection,
            )
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        else -> ModernMarkdownFallbackBlock(node, content, tokenSpec, enableTextSelection)
    }
}

@Composable
private fun ModernMarkdownHeading(
    node: ASTNode,
    content: String,
    style: TextStyle,
    tokenSpec: ModernMarkdownTokenSpec,
    enableTextSelection: Boolean,
) {
    val annotatedText =
        remember(content, node, style, tokenSpec) {
            buildModernMarkdownHeadingAnnotatedText(
                content = content,
                node = node,
                style = style,
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
                .padding(top = 12.dp, bottom = 4.dp),
        selectable = enableTextSelection,
    )
}

@Composable
private fun ModernMarkdownParagraph(
    node: ASTNode,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    textStyle: TextStyle,
    onImageClick: ((String) -> Unit)?,
    enableTextSelection: Boolean,
) {
    val items =
        remember(content, node, tokenSpec, textStyle) {
            buildModernParagraphItems(
                content = content,
                paragraphNode = node,
                tokenSpec = tokenSpec,
                textStyle = textStyle,
            )
        }
    if (items.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEach { item ->
            when (item) {
                is ModernParagraphItem.Text -> {
                    MDText(
                        text = item.text,
                        style = textStyle,
                        enableTextSelection = enableTextSelection,
                    )
                }

                is ModernParagraphItem.Image -> {
                    MDImage(
                        image = item.image.toCommonMarkImage(),
                        onImageClick = onImageClick,
                    )
                }

                is ModernParagraphItem.Gallery -> {
                    MDImageGallery(
                        images = item.images.map(ModernMarkdownImage::toCommonMarkImage).toImmutableList(),
                        onImageClick = onImageClick,
                    )
                }

                is ModernParagraphItem.VoiceMemo -> {
                    com.lomo.ui.component.media.AudioPlayerCard(relativeFilePath = item.url)
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
        )
    }
}

@Composable
private fun ModernMarkdownIndentedCodeBlock(
    node: ASTNode,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    enableTextSelection: Boolean,
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
    )
}

@Composable
private fun ModernMarkdownBlockQuote(
    node: ASTNode,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    enableTextSelection: Boolean,
) {
    Row(modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
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
            modifier =
                Modifier
                    .padding(start = 8.dp)
                    .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(tokenSpec.blockSpacing),
        ) {
            node.children
                .filter(::isRenderableNestedBlock)
                .forEach { child ->
                    ModernMarkdownBlock(
                        node = child,
                        content = content,
                        tokenSpec = tokenSpec,
                        onTodoClick = onTodoClick,
                        todoOverrides = todoOverrides,
                        onImageClick = onImageClick,
                        enableTextSelection = enableTextSelection,
                    )
                }
        }
    }
}

@Composable
private fun ModernMarkdownFallbackBlock(
    node: ASTNode,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    enableTextSelection: Boolean,
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
    )
}

private fun ASTNode.extractCodeFenceContent(content: String): String =
    children
        .filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
        .joinToString(separator = "\n") { it.extractNodeText(content) }

private fun ASTNode.extractIndentedCodeContent(content: String): String =
    children
        .filter { it.type == MarkdownTokenTypes.CODE_LINE }
        .joinToString(separator = "\n") { it.extractNodeText(content) }

private fun isRenderableNestedBlock(node: ASTNode): Boolean = node.type != MarkdownTokenTypes.EOL
