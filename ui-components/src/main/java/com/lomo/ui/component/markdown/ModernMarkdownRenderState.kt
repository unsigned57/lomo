package com.lomo.ui.component.markdown

import androidx.compose.runtime.Immutable

@Immutable
internal sealed interface ModernMarkdownRenderState {
    data class Ready(
        val plan: ModernMarkdownRenderPlan,
    ) : ModernMarkdownRenderState

    data class Pending(
        val fallbackText: String,
    ) : ModernMarkdownRenderState
}
