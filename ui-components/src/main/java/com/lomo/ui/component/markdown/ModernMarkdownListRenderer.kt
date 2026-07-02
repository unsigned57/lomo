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
import com.lomo.ui.text.MemoTextSelectionRegistrar
import com.lomo.ui.theme.memoListTextStyle
import com.lomo.ui.theme.memoParagraphBlockSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableMap

@Composable
internal fun ModernMarkdownList(
    renderNode: ModernMarkdownRenderNode.ListBlock,
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
                .padding(start = MarkdownComponentTokens.ListIndent),
        verticalArrangement = Arrangement.spacedBy(tokenSpec.blockSpacing),
    ) {
        renderNode.items.forEachIndexed { index, item ->
                val bullet =
                    if (renderNode.ordered) {
                        item.node.extractOrderedListMarker(content) ?: "${renderNode.startNumber + index}."
                    } else {
                        "•"
                    }
                ModernMarkdownListItem(
                    content = content,
                    item = item,
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
}

@Composable
private fun ModernMarkdownListItem(
    content: String,
    item: ModernMarkdownListItemRenderNode,
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
    val presentation = remember(content, item.node, todoOverrides) {
        resolveModernTaskListPresentation(
            content = content,
            listItemNode = item.node,
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
                .padding(vertical = MarkdownComponentTokens.ListItemVerticalPadding),
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
            item.blocks
                .forEach { child ->
                    ModernMarkdownBlock(
                        renderNode = child,
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
                Modifier.size(MarkdownComponentTokens.LeadingVisualWidth)
            } else {
                Modifier
                    .size(MarkdownComponentTokens.LeadingVisualWidth)
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
            Canvas(modifier = Modifier.size(MarkdownComponentTokens.BulletSize)) { drawCircle(color = dotColor) }
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
        modifier = Modifier.width(MarkdownComponentTokens.LeadingSlotWidth),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .width(MarkdownComponentTokens.LeadingVisualWidth)
                    .height(MarkdownComponentTokens.LeadingVisualWidth),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
