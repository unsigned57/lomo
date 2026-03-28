package com.lomo.ui.component.markdown

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
internal fun ModernMarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    maxVisibleBlocks: Int = Int.MAX_VALUE,
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    todoOverrides: Map<Int, Boolean> = emptyMap(),
    onImageClick: ((String) -> Unit)? = null,
    onTotalBlocks: ((Int) -> Unit)? = null,
    knownTagsToStrip: List<String> = emptyList(),
    enableTextSelection: Boolean = false,
) {
    val renderPlan =
        remember(content, maxVisibleBlocks, knownTagsToStrip) {
            createModernMarkdownRenderPlan(
                content = content,
                maxVisibleBlocks = maxVisibleBlocks,
                knownTagsToStrip = knownTagsToStrip,
            )
        }

    LaunchedEffect(renderPlan.totalBlocks, onTotalBlocks) {
        onTotalBlocks?.invoke(renderPlan.totalBlocks)
    }

    val contentRenderer: @Composable () -> Unit = {
        ModernMarkdownRenderPlanContent(
            plan = renderPlan,
            modifier = modifier,
            onTodoClick = onTodoClick,
            todoOverrides = todoOverrides,
            onImageClick = onImageClick,
            enableTextSelection = enableTextSelection,
        )
    }

    if (enableTextSelection) {
        SelectionContainer {
            contentRenderer()
        }
    } else {
        contentRenderer()
    }
}
