package com.lomo.ui.component.input

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.theme.memoEditorTextStyle
import com.lomo.ui.theme.memoHintTextStyle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun rememberInputEditorTextStyles(inputText: String, hintText: String) = run {
    val baseInputTextStyle = MaterialTheme.typography.memoEditorTextStyle()
    val baseHintTextStyle = MaterialTheme.typography.memoHintTextStyle()
    val inputStyle = remember(baseInputTextStyle, inputText) { baseInputTextStyle.scriptAwareFor(inputText) }
    val hintStyle = remember(baseHintTextStyle, hintText) { baseHintTextStyle.scriptAwareFor(hintText) }
    inputStyle to hintStyle
}

@Composable
internal fun rememberResolvedInputToolbarTools(
    surface: InputEditorSurfaceState,
    showTagSelector: Boolean,
): ImmutableList<InputToolbarTool> {
    val toolbarRegistry =
        remember(
            surface.capabilities.toolbarTools,
            showTagSelector,
        ) {
            val highlightedCommands =
                if (showTagSelector) {
                    setOf(InputEditorCommand.ToggleTagSelector)
                } else {
                    emptySet()
                }
            InputToolbarRegistry.create(
                InputToolbarRegistryState(
                    tools = surface.capabilities.toolbarTools,
                    highlightedCommands = highlightedCommands,
                ),
            )
        }
    val persistedOrder =
        remember(surface.toolbarOrder) {
            surface.toolbarOrder.map(InputToolbarActionId::persistedId)
        }
    return remember(toolbarRegistry, persistedOrder) {
        toolbarRegistry.resolveTools(persistedOrder)
    }
}

@Composable
internal fun rememberInputEditorChrome(
    isExpanded: Boolean,
    displayMode: InputEditorDisplayMode,
    inputText: String,
    hintText: String,
) = remember(isExpanded, displayMode, inputText, hintText) {
    resolveInputEditorChrome(isExpanded, displayMode, inputText, hintText)
}

@Composable
internal fun rememberInputHeaderTools(tools: ImmutableList<InputToolbarTool>) = remember(tools) {
    tools.filter { it.placement == InputToolbarToolPlacement.HeaderEnd }.toImmutableList()
}
