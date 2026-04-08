package com.lomo.ui.component.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun resolveModernMarkdownRenderState(
    basePlan: ModernMarkdownRenderPlan?,
    content: String,
    maxVisibleBlocks: Int,
    knownTagsToStrip: ImmutableList<String>,
): ModernMarkdownRenderState =
    if (basePlan == null) {
        ModernMarkdownRenderState.Pending(
            fallbackText =
                buildPendingModernMarkdownFallbackText(
                    content = content,
                    knownTagsToStrip = knownTagsToStrip,
                ),
        )
    } else {
        ModernMarkdownRenderState.Ready(
            plan =
                if (maxVisibleBlocks == Int.MAX_VALUE) {
                    basePlan
                } else {
                    limitModernMarkdownRenderPlan(basePlan, maxVisibleBlocks)
                },
        )
    }

internal fun buildPendingModernMarkdownFallbackText(
    content: String,
    knownTagsToStrip: ImmutableList<String>,
): String =
    sanitizeModernMarkdownKnownTags(
        content = content,
        tags = knownTagsToStrip,
    ).trim().ifBlank { content.trim() }

@Composable
internal fun ModernMarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    maxVisibleBlocks: Int = Int.MAX_VALUE,
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    todoOverrides: ImmutableMap<Int, Boolean> = persistentHashMapOf(),
    onImageClick: ((String) -> Unit)? = null,
    onTotalBlocks: ((Int) -> Unit)? = null,
    precomputedRenderPlan: ModernMarkdownRenderPlan? = null,
    knownTagsToStrip: ImmutableList<String> = persistentListOf(),
    enableTextSelection: Boolean = false,
) {
    val basePlan by
        produceState<ModernMarkdownRenderPlan?>(
            initialValue = precomputedRenderPlan,
            key1 = content,
            key2 = precomputedRenderPlan,
            key3 = knownTagsToStrip,
        ) {
            value =
                precomputedRenderPlan
                    ?: withContext(Dispatchers.Default) {
                        createModernMarkdownRenderPlan(
                            content = content,
                            knownTagsToStrip = knownTagsToStrip,
                        )
                    }
        }
    val renderState =
        remember(basePlan, content, maxVisibleBlocks, knownTagsToStrip) {
            resolveModernMarkdownRenderState(
                basePlan = basePlan,
                content = content,
                maxVisibleBlocks = maxVisibleBlocks,
                knownTagsToStrip = knownTagsToStrip,
            )
        }
    val readyPlan = (renderState as? ModernMarkdownRenderState.Ready)?.plan

    LaunchedEffect(readyPlan?.totalBlocks, onTotalBlocks) {
        readyPlan?.let { plan -> onTotalBlocks?.invoke(plan.totalBlocks) }
    }

    val contentRenderer: @Composable () -> Unit = {
        when (renderState) {
            is ModernMarkdownRenderState.Pending -> {
                MarkdownRendererFallback(
                    content = renderState.fallbackText,
                    modifier = modifier,
                    enableTextSelection = enableTextSelection,
                )
            }

            is ModernMarkdownRenderState.Ready -> {
                ModernMarkdownRenderPlanContent(
                    plan = renderState.plan,
                    modifier = modifier,
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    onImageClick = onImageClick,
                    enableTextSelection = enableTextSelection,
                )
            }
        }
    }

    contentRenderer()
}
