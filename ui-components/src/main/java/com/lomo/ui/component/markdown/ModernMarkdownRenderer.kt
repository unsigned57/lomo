package com.lomo.ui.component.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.lomo.ui.text.MemoTextSelectionRegistrar
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
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
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
                    normalizeModernMarkdownRenderPlanForMediaResolver(
                        plan = basePlan,
                        mediaPresentationResolver = mediaPresentationResolver,
                    )
                } else {
                    limitModernMarkdownRenderPlan(
                        plan = basePlan,
                        maxVisibleBlocks = maxVisibleBlocks,
                        mediaPresentationResolver = mediaPresentationResolver,
                    )
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
    ).content.trim().ifBlank { content.trim() }

@Composable
internal fun ModernMarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    maxVisibleBlocks: Int = Int.MAX_VALUE,
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    todoOverrides: ImmutableMap<Int, Boolean> = persistentHashMapOf(),
    onImageClick: ((String) -> Unit)? = null,
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
    onTotalBlocks: ((Int) -> Unit)? = null,
    precomputedRenderPlan: ModernMarkdownRenderPlan? = null,
    knownTagsToStrip: ImmutableList<String> = persistentListOf(),
    enableTextSelection: Boolean = false,
    textSelectionRegistrar: MemoTextSelectionRegistrar? = null,
    onTextTapFeedback: (() -> Unit)? = null,
    onTextBodyClick: (() -> Unit)? = null,
    onTextDoubleClick: (() -> Unit)? = null,
    onTextLongClick: (() -> Unit)? = null,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)? = null,
) {
    val effectiveMediaPresentationResolver = mediaPresentationResolver
    val effectiveMediaContent = mediaContent
    val effectivePlanningMediaResolver = effectiveMediaPresentationResolver.takeIf { effectiveMediaContent != null }

    val basePlan by
        produceState<ModernMarkdownRenderPlan?>(
            initialValue = precomputedRenderPlan,
            key1 = content,
            key2 = precomputedRenderPlan,
            key3 = knownTagsToStrip to effectivePlanningMediaResolver,
        ) {
            value =
                precomputedRenderPlan
                    ?: withContext(Dispatchers.Default) {
                        createModernMarkdownRenderPlan(
                            content = content,
                            knownTagsToStrip = knownTagsToStrip,
                            mediaPresentationResolver = effectivePlanningMediaResolver,
                        )
                    }
        }
    val renderState =
        remember(basePlan, content, maxVisibleBlocks, knownTagsToStrip, effectivePlanningMediaResolver) {
            resolveModernMarkdownRenderState(
                basePlan = basePlan,
                content = content,
                maxVisibleBlocks = maxVisibleBlocks,
                knownTagsToStrip = knownTagsToStrip,
                mediaPresentationResolver = effectivePlanningMediaResolver,
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
                    textSelectionRegistrar = textSelectionRegistrar,
                    onTextTapFeedback = onTextTapFeedback,
                    onTextBodyClick = onTextBodyClick,
                    onTextDoubleClick = onTextDoubleClick,
                    onTextLongClick = onTextLongClick,
                )
            }

            is ModernMarkdownRenderState.Ready -> {
                ModernMarkdownRenderPlanContent(
                    plan = renderState.plan,
                    modifier = modifier,
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    onImageClick = onImageClick,
                    mediaPresentationResolver = effectiveMediaPresentationResolver,
                    enableTextSelection = enableTextSelection,
                    textSelectionRegistrar = textSelectionRegistrar,
                    onTextTapFeedback = onTextTapFeedback,
                    onTextBodyClick = onTextBodyClick,
                    onTextDoubleClick = onTextDoubleClick,
                    onTextLongClick = onTextLongClick,
                    hideImages = hideImages,
                    mediaContent = effectiveMediaContent,
                )
            }
        }
    }

    contentRenderer()
}
