package com.lomo.ui.component.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.*
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.WithDraggableScrollbar
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.theme.memoEditorTextStyle
import com.lomo.ui.theme.memoHintTextStyle
import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay

@Composable
internal fun InputEditorPanel(
    presentationState: InputSheetPresentationState,
    inputValue: TextFieldValue,
    previewContent: String?,
    hintText: String,
    availableTags: ImmutableList<String>,
    showTagSelector: Boolean,
    focusRequester: FocusRequester,
    onTextChange: (TextFieldValue) -> Unit,
    onTagSelected: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    onDisplayModeChange: (InputEditorDisplayMode) -> Unit,
    surface: InputEditorSurfaceState,
    onEditorCommand: (InputEditorCommand) -> Unit,
    onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
    onSubmit: () -> Unit,
    benchmarkEditorTag: String?,
    benchmarkSubmitTag: String?,
    slots: InputSheetSlots,
    haptic: AppHapticFeedback,
) {
    val motionStage = presentationState.surfaceMotionStage()
    val isExpanded = motionStage != InputSheetMotionStage.Compact
    val displayMode = presentationState.effectiveDisplayMode()
    val typography = MaterialTheme.typography
    val baseInputTextStyle = typography.memoEditorTextStyle()
    val baseHintTextStyle = typography.memoHintTextStyle()
    val inputTextStyle =
        remember(baseInputTextStyle, inputValue.text) {
            baseInputTextStyle.scriptAwareFor(inputValue.text)
        }
    val hintTextStyle =
        remember(baseHintTextStyle, hintText) {
            baseHintTextStyle.scriptAwareFor(hintText)
        }
    val chromeState =
        remember(presentationState, inputValue.text, hintText) {
            resolveInputEditorChromeState(
                presentationState = presentationState,
                inputText = inputValue.text,
                hintText = hintText,
            )
        }
    val editorAlpha by rememberInputEditorLayerAlpha(
        visible = presentationState.showsEditorContent(),
        label = "InputEditorAlpha",
    )
    val previewAlpha by rememberInputEditorLayerAlpha(
        visible = presentationState.showsPreviewLayer(),
        label = "InputPreviewAlpha",
    )
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                .padding(InputSheetTokens.PanelPadding),
    ) {
        InputEditorChromeTransitionHost(
            transitionState = chromeState.displayModeBar,
        ) { chromeModifier ->
            InputEditorDisplayModeBar(
                displayMode = displayMode,
                onDisplayModeChange = onDisplayModeChange,
                onCollapse = onToggleExpanded,
                enabled = chromeState.displayModeBar.isInteractive,
                haptic = haptic,
                modifier = chromeModifier,
            )
        }
        val actionBadge = surface.actionBadge
        InputEditorActionBadgeContent(
            badge = actionBadge,
            onClick = {
                if (actionBadge != null) {
                    onEditorCommand(actionBadge.command)
                }
            },
        )
        InputEditorBodyContent(
            isExpanded = isExpanded,
            chromeState = chromeState,
            inputValue = inputValue,
            previewContent = previewContent,
            hintText = hintText,
            focusRequester = focusRequester,
            onTextChange = onTextChange,
            inputTextStyle = inputTextStyle,
            hintTextStyle = hintTextStyle,
            editorAlpha = editorAlpha,
            previewAlpha = previewAlpha,
            benchmarkEditorTag = benchmarkEditorTag,
        )
        InputEditorTagSelector(
            availableTags = availableTags,
            showTagSelector = showTagSelector && chromeState.formattingToolbar.isInteractive,
            slots = slots,
            onTagSelected = onTagSelected,
        )
        InputEditorToolbarSection(
            chromeState = chromeState,
            showTagSelector = showTagSelector,
            isExpanded = isExpanded,
            isSubmitEnabled = inputValue.text.isNotBlank(),
            onToggleExpanded = onToggleExpanded,
            surface = surface,
            onEditorCommand = onEditorCommand,
            onToolbarOrderChanged = onToolbarOrderChanged,
            onSubmit = onSubmit,
            benchmarkSubmitTag = benchmarkSubmitTag,
            haptic = haptic,
        )
    }
}

@Composable
private fun ColumnScope.InputEditorBodyContent(
    isExpanded: Boolean,
    chromeState: InputEditorChromeState,
    inputValue: TextFieldValue,
    previewContent: String?,
    hintText: String,
    focusRequester: FocusRequester,
    onTextChange: (TextFieldValue) -> Unit,
    inputTextStyle: androidx.compose.ui.text.TextStyle,
    hintTextStyle: androidx.compose.ui.text.TextStyle,
    editorAlpha: Float,
    previewAlpha: Float,
    benchmarkEditorTag: String?,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (isExpanded) Modifier.weight(1f) else Modifier),
    ) {
        InputEditorTextField(
            isExpanded = isExpanded,
            showsPlaceholder = chromeState.showsPlaceholder,
            inputValue = inputValue,
            hintText = hintText,
            focusRequester = focusRequester,
            textStyle = inputTextStyle,
            placeholderTextStyle = hintTextStyle,
            benchmarkEditorTag = benchmarkEditorTag,
            modifier =
                if (isExpanded) {
                    Modifier
                        .fillMaxSize()
                        .alpha(editorAlpha)
                } else {
                    Modifier.alpha(editorAlpha)
                },
            onTextChange = onTextChange,
        )
        if (chromeState.showsPreviewContent) {
            InputEditorPreviewContent(
                content = resolveInputEditorPreviewContent(inputValue.text, previewContent),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alpha(previewAlpha),
            )
        }
    }
}

@Composable
private fun InputEditorToolbarSection(
    chromeState: InputEditorChromeState,
    showTagSelector: Boolean,
    isExpanded: Boolean,
    isSubmitEnabled: Boolean,
    onToggleExpanded: () -> Unit,
    surface: InputEditorSurfaceState,
    onEditorCommand: (InputEditorCommand) -> Unit,
    onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
    onSubmit: () -> Unit,
    benchmarkSubmitTag: String?,
    haptic: AppHapticFeedback,
) {
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
    val toolbarTools =
        remember(toolbarRegistry, persistedOrder) {
            toolbarRegistry.resolveTools(persistedOrder)
        }
    InputEditorChromeTransitionHost(
        transitionState = chromeState.formattingToolbar,
    ) { chromeModifier ->
        InputEditorToolbar(
            toggleIcon = chromeState.toggleIcon,
            isExpanded = isExpanded,
            isSubmitEnabled = isSubmitEnabled,
            enabled = chromeState.formattingToolbar.isInteractive,
            onToggleExpanded = onToggleExpanded,
            tools = toolbarTools,
            onEditorCommand = onEditorCommand,
            onToolbarOrderChanged = onToolbarOrderChanged,
            onSubmit = onSubmit,
            benchmarkSubmitTag = benchmarkSubmitTag,
            haptic = haptic,
            modifier = chromeModifier.padding(top = InputSheetTokens.ToolbarTopPadding),
        )
    }
}

private data class InputEditorChromeMotion(
    val alpha: Float,
    val offsetY: Dp,
)

@Composable
private fun rememberInputEditorChromeMotion(
    transitionState: InputEditorChromeTransitionState,
): InputEditorChromeMotion {
    val alpha by animateFloatAsState(
        targetValue = if (transitionState.isVisible) 1f else 0f,
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "InputEditorChromeAlpha",
    )
    val offsetY by animateDpAsState(
        targetValue = if (transitionState.isVisible) InputSheetTokens.CollapsedInset else transitionState.hiddenOffsetY,
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "InputEditorChromeOffsetY",
    )
    return remember(alpha, offsetY) {
        InputEditorChromeMotion(
            alpha = alpha,
            offsetY = offsetY,
        )
    }
}

@Composable
private fun InputEditorChromeTransitionHost(
    transitionState: InputEditorChromeTransitionState,
    content: @Composable (Modifier) -> Unit,
) {
    if (!transitionState.keepsHostMounted) {
        return
    }

    val chromeMotion = rememberInputEditorChromeMotion(transitionState)
    val chromeModifier =
        Modifier
            .fillMaxWidth()
            .offset(y = chromeMotion.offsetY)
            .alpha(chromeMotion.alpha)
            .then(
                if (transitionState.isInteractive) {
                    Modifier
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            )
    content(chromeModifier)
}

@Composable
private fun InputEditorDisplayModeBar(
    displayMode: InputEditorDisplayMode,
    onDisplayModeChange: (InputEditorDisplayMode) -> Unit,
    onCollapse: () -> Unit,
    enabled: Boolean,
    haptic: AppHapticFeedback,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = InputSheetTokens.SegmentedControlShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(InputSheetTokens.SegmentedControlContentPadding),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InputEditorDisplayModePill(
                    label = stringResource(Res.string.input_mode_edit),
                    selected = displayMode == InputEditorDisplayMode.Edit,
                    enabled = enabled,
                    onClick = {
                        handleInputEditorDisplayModeTapAction(
                            action =
                                resolveInputEditorDisplayModeTapAction(
                                    currentMode = displayMode,
                                    tappedMode = InputEditorDisplayMode.Edit,
                                ),
                            onDisplayModeChange = onDisplayModeChange,
                            onCollapse = onCollapse,
                        )
                    },
                )
                InputEditorDisplayModePill(
                    label = stringResource(Res.string.input_mode_preview),
                    selected = displayMode == InputEditorDisplayMode.Preview,
                    enabled = enabled,
                    onClick = {
                        handleInputEditorDisplayModeTapAction(
                            action =
                                resolveInputEditorDisplayModeTapAction(
                                    currentMode = displayMode,
                                    tappedMode = InputEditorDisplayMode.Preview,
                                ),
                            onDisplayModeChange = onDisplayModeChange,
                            onCollapse = onCollapse,
                        )
                    },
                )
            }
        }
        Spacer(modifier = Modifier.weight(weight = 1f))
        InputToolbarIconButton(
            icon = Icons.Rounded.KeyboardArrowDown,
            contentDescription = stringResource(Res.string.cd_collapse),
            onClick = onCollapse,
            enabled = enabled,
            haptic = haptic,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun handleInputEditorDisplayModeTapAction(
    action: InputEditorDisplayModeTapAction,
    onDisplayModeChange: (InputEditorDisplayMode) -> Unit,
    onCollapse: () -> Unit,
) {
    when (action) {
        InputEditorDisplayModeTapAction.Collapse -> onCollapse()
        is InputEditorDisplayModeTapAction.ChangeMode -> onDisplayModeChange(action.mode)
    }
}

@Composable
private fun InputEditorDisplayModePill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = InputSheetTokens.ModePillShape,
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
        modifier =
            Modifier
                .clip(InputSheetTokens.ModePillShape)
                .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(InputSheetTokens.ModePillContentPadding),
        )
    }
}

@Composable
private fun InputEditorPreviewContent(
    content: String,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = InputSheetTokens.PreviewShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (content.isBlank()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = InputSheetTokens.PreviewHorizontalPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.input_preview_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            WithDraggableScrollbar(
                state = scrollState,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(
                                horizontal = InputSheetTokens.EditorContainerPaddingHorizontal,
                                vertical = InputSheetTokens.EditorContainerPaddingVertical,
                            ),
                ) {
                    MarkdownRenderer(
                        content = content,
                        modifier = Modifier.fillMaxWidth(),
                        enableTextSelection = true,
                    )
                }
            }
        }
    }
}

internal fun resolveInputEditorPreviewContent(
    inputText: String,
    previewContent: String?,
): String = previewContent ?: inputText


@Composable
private fun InputEditorTagSelector(
    availableTags: ImmutableList<String>,
    showTagSelector: Boolean,
    slots: InputSheetSlots,
    onTagSelected: (String) -> Unit,
) {
    AnimatedVisibility(
        visible = showTagSelector && availableTags.isNotEmpty(),
        enter =
            expandVertically(
                animationSpec =
                    androidx.compose.animation.core.tween(
                        durationMillis = MotionTokens.DurationMedium2,
                        easing = MotionTokens.EasingEmphasized,
                    ),
            ) +
                fadeIn(
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = MotionTokens.DurationMedium2,
                        ),
                ),
        exit =
            shrinkVertically(
                animationSpec =
                    androidx.compose.animation.core.tween(
                        durationMillis = MotionTokens.DurationMedium2,
                        easing = MotionTokens.EasingEmphasized,
                    ),
            ) +
                fadeOut(
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = MotionTokens.DurationShort4,
                        ),
                ),
    ) {
        Column {
            Spacer(modifier = Modifier.height(AppSpacing.MediumSmall))
            slots.tagSelectorBar(
                TagSelectorBarState(availableTags = availableTags),
                TagSelectorBarCallbacks(onTagSelected = onTagSelected),
            )
        }
    }
}
