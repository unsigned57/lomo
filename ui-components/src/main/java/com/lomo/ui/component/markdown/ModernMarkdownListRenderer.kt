package com.lomo.ui.component.markdown

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lomo.ui.text.MemoTextSelectionRegistrar
import com.lomo.ui.theme.memoListTextStyle
import com.lomo.ui.theme.memoParagraphBlockSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableMap
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

@Composable
internal fun ModernMarkdownUnorderedList(
    node: ASTNode,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    enableTextSelection: Boolean,
    textSelectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(tokenSpec.blockSpacing),
    ) {
        node.children
            .filter { it.type == MarkdownElementTypes.LIST_ITEM }
            .forEach { listItemNode ->
                ModernMarkdownListItem(
                    content = content,
                    listItemNode = listItemNode,
                    tokenSpec = tokenSpec,
                    bullet = "•",
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    onImageClick = onImageClick,
                    mediaPresentationResolver = mediaPresentationResolver,
                    mediaContent = mediaContent,
                    enableTextSelection = enableTextSelection,
                    textSelectionRegistrar = textSelectionRegistrar,
                    onTextTapFeedback = onTextTapFeedback,
                    onTextBodyClick = onTextBodyClick,
                    onTextDoubleClick = onTextDoubleClick,
                    onTextLongClick = onTextLongClick,
                    hideImages = hideImages,
                )
            }
    }
}

@Composable
internal fun ModernMarkdownOrderedList(
    node: ASTNode,
    content: String,
    tokenSpec: ModernMarkdownTokenSpec,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    enableTextSelection: Boolean,
    textSelectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(tokenSpec.blockSpacing),
    ) {
        node.children
            .filter { it.type == MarkdownElementTypes.LIST_ITEM }
            .forEachIndexed { index, listItemNode ->
                val bullet = listItemNode.extractOrderedListMarker(content) ?: "${index + 1}."
                ModernMarkdownListItem(
                    content = content,
                    listItemNode = listItemNode,
                    tokenSpec = tokenSpec,
                    bullet = bullet,
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    onImageClick = onImageClick,
                    mediaPresentationResolver = mediaPresentationResolver,
                    mediaContent = mediaContent,
                    enableTextSelection = enableTextSelection,
                    textSelectionRegistrar = textSelectionRegistrar,
                    onTextTapFeedback = onTextTapFeedback,
                    onTextBodyClick = onTextBodyClick,
                    onTextDoubleClick = onTextDoubleClick,
                    onTextLongClick = onTextLongClick,
                    hideImages = hideImages,
                )
            }
    }
}@Composable
private fun ModernMarkdownListItem(
    content: String,
    listItemNode: ASTNode,
    tokenSpec: ModernMarkdownTokenSpec,
    bullet: String,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    enableTextSelection: Boolean,
    textSelectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    val presentation = remember(content, listItemNode, todoOverrides) {
        resolveModernTaskListPresentation(
            content = content,
            listItemNode = listItemNode,
            todoOverrides = todoOverrides,
        )
    }
    val checkedItemStyle =
        tokenSpec.listStyle.copy(
            textDecoration = TextDecoration.LineThrough,
            color = MaterialTheme.colorScheme.outline,
        )
    val itemStyle = if (presentation.effectiveChecked) checkedItemStyle else tokenSpec.listStyle
 
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModernMarkdownListItemLeading(
            bullet = bullet,
            presentation = presentation,
            onTodoClick = onTodoClick,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(memoParagraphBlockSpacing()),
        ) {
            listItemNode.children
                .filter(::isRenderableListItemChild)
                .forEach { child ->
                    ModernMarkdownBlock(
                        node = child,
                        content = content,
                        tokenSpec = tokenSpec,
                        onTodoClick = onTodoClick,
                        todoOverrides = todoOverrides,
                        onImageClick = onImageClick,
                        mediaPresentationResolver = mediaPresentationResolver,
                        mediaContent = mediaContent,
                        enableTextSelection = enableTextSelection,
                        textSelectionRegistrar = textSelectionRegistrar,
                        onTextTapFeedback = onTextTapFeedback,
                        onTextBodyClick = onTextBodyClick,
                        onTextDoubleClick = onTextDoubleClick,
                        onTextLongClick = onTextLongClick,
                        baseParagraphStyle = itemStyle,
                        hideImages = hideImages,
                    )
                }
        }
    }
}

@Composable
private fun ModernMarkdownListItemLeading(
    bullet: String,
    presentation: ModernTaskListPresentation,
    onTodoClick: ((Int, Boolean) -> Unit)?,
) {
    if (presentation.isTask) {
        val checkboxScale by animateFloatAsState(
            targetValue = if (onTodoClick == null || presentation.effectiveChecked) 1.0f else 0.92f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            label = "modern_checkbox_scale",
        )
        val checkboxModifier =
            if (onTodoClick == null) {
                Modifier.size(MODERN_MARKDOWN_LEADING_VISUAL_WIDTH)
            } else {
                Modifier
                    .size(MODERN_MARKDOWN_LEADING_VISUAL_WIDTH)
                    .graphicsLayer {
                        scaleX = checkboxScale
                        scaleY = checkboxScale
                    }
            }
        ModernMarkdownListLeadingSlot {
            Checkbox(
                checked = presentation.effectiveChecked,
                onCheckedChange =
                    if (onTodoClick == null) {
                        null
                    } else {
                        val appHaptic = LocalAppHapticFeedback.current
                        { checked ->
                            appHaptic.medium()
                            onTodoClick(presentation.sourceLine, checked)
                        }
                    },
                modifier = checkboxModifier,
            )
        }
    } else {
        ModernMarkdownBulletLeading(bullet)
    }
}

@Composable
private fun ModernMarkdownBulletLeading(bullet: String) {
    if (bullet == "•") {
        ModernMarkdownListLeadingSlot {
            val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(modifier = Modifier.size(6.dp)) { drawCircle(color = dotColor) }
        }
    } else {
        ModernMarkdownListLeadingSlot {
            Text(
                text = bullet,
                style =
                    MaterialTheme.typography
                        .memoListTextStyle()
                        .copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModernMarkdownListLeadingSlot(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier.width(MODERN_MARKDOWN_LEADING_SLOT_WIDTH),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .width(MODERN_MARKDOWN_LEADING_VISUAL_WIDTH)
                    .height(MODERN_MARKDOWN_LEADING_VISUAL_WIDTH),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

private val MODERN_MARKDOWN_LEADING_SLOT_WIDTH = 28.dp
private val MODERN_MARKDOWN_LEADING_VISUAL_WIDTH = 24.dp

private fun ASTNode.extractOrderedListMarker(content: String): String? =
    children
        .firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
        ?.extractNodeText(content)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun isRenderableListItemChild(node: ASTNode): Boolean =
    node.type != MarkdownTokenTypes.LIST_BULLET &&
        node.type != MarkdownTokenTypes.LIST_NUMBER &&
        node.type != GFMTokenTypes.CHECK_BOX &&
        node.type != MarkdownTokenTypes.EOL
