package com.lomo.ui.component.markdown

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.memoListTextStyle
import com.lomo.ui.theme.memoParagraphBlockSpacing
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
    enableTextSelection: Boolean,
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
                    enableTextSelection = enableTextSelection,
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
    enableTextSelection: Boolean,
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
                    enableTextSelection = enableTextSelection,
                )
            }
    }
}

@Composable
private fun ModernMarkdownListItem(
    content: String,
    listItemNode: ASTNode,
    tokenSpec: ModernMarkdownTokenSpec,
    bullet: String,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    enableTextSelection: Boolean,
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
                        enableTextSelection = enableTextSelection,
                        baseParagraphStyle = itemStyle,
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
        val appHaptic = com.lomo.ui.util.LocalAppHapticFeedback.current
        val scale by animateFloatAsState(
            targetValue = if (presentation.effectiveChecked) 1.0f else 0.92f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            label = "modern_checkbox_scale",
        )
        Checkbox(
            checked = presentation.effectiveChecked,
            onCheckedChange = { checked ->
                appHaptic.medium()
                onTodoClick?.invoke(presentation.sourceLine, checked)
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
        ModernMarkdownBulletLeading(bullet)
    }
}

@Composable
private fun ModernMarkdownBulletLeading(bullet: String) {
    if (bullet == "•") {
        Box(
            modifier =
                Modifier
                    .width(24.dp)
                    .height(24.dp)
                    .padding(end = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(modifier = Modifier.size(6.dp)) { drawCircle(color = dotColor) }
        }
    } else {
        Text(
            text = bullet,
            style =
                MaterialTheme.typography
                    .memoListTextStyle()
                    .copy(fontWeight = FontWeight.Bold),
            modifier =
                Modifier
                    .width(20.dp)
                    .padding(end = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
