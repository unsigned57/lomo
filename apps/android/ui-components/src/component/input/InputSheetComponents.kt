package com.lomo.ui.component.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
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
import com.lomo.ui.generated.resources.cd_collapse
import com.lomo.ui.generated.resources.input_mode_edit
import com.lomo.ui.generated.resources.input_mode_preview
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.WithDraggableScrollbar
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.component.markdown.MarkdownRenderState
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.memoEditorTextStyle
import com.lomo.ui.theme.memoHintTextStyle
import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay

@Composable
internal fun InputEditorPanel(
    presentation: InputEditorPanelState,
    callbacks: InputEditorPanelCallbacks,
    benchmarkEditorTag: String?,
    benchmarkSubmitTag: String?,
    haptic: AppHapticFeedback,
) {
    val presentationState = presentation.presentationState
    val inputValue = presentation.inputValue
    val hintText = presentation.hintText
    val isExpanded = presentationState.surfaceMotionStage() != InputSheetMotionStage.Compact
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
        visible = { it.showsEditorContent() && it != InputSheetPresentationState.SwitchingToPreview },
        label = "InputEditorAlpha",
    )
    val previewAlpha by rememberInputEditorLayerAlpha(
        visible = { it.showsPreviewLayer() && it != InputSheetPresentationState.SwitchingToEdit },
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
            motionTarget = ::resolveInputEditorDisplayModeBarTransitionState,
        ) { chromeModifier ->
            InputEditorDisplayModeBar(
                displayMode = displayMode,
                onDisplayModeChange = callbacks.onDisplayModeChange,
                onCollapse = callbacks.onToggleExpanded,
                enabled = chromeState.displayModeBar.isInteractive,
                haptic = haptic,
                modifier = chromeModifier,
            )
        }
        InputEditorActionBadgeHost(
            badge = presentation.surface.actionBadge,
            onEditorCommand = callbacks.onEditorCommand,
        )
        InputEditorBodyContent(
            state =
                InputEditorBodyState(
                    editorInteractive = presentationState.prefersEditorFocus(),
                    isExpanded = isExpanded,
                    chromeState = chromeState,
                    inputValue = inputValue,
                    previewState = presentation.surface.previewState,
                    hintText = hintText,
                    focusRequester = presentation.focusRequester,
                    inputTextStyle = inputTextStyle,
                    hintTextStyle = hintTextStyle,
                ),
            editorAlpha = editorAlpha,
            previewAlpha = previewAlpha,
            benchmarkEditorTag = benchmarkEditorTag,
            onTextChange = callbacks.onTextChange,
        )
        InputEditorTagSelector(
            availableTags = presentation.availableTags,
            showTagSelector = presentation.showTagSelector && chromeState.formattingToolbar.isInteractive,
            slots = presentation.slots,
            onTagSelected = callbacks.onTagSelected,
        )
        InputEditorToolbarSection(
            chromeState = chromeState,
            showTagSelector = presentation.showTagSelector,
            isExpanded = isExpanded,
            isSubmitEnabled = inputValue.text.isNotBlank(),
            surface = presentation.surface,
            callbacks =
                InputEditorToolbarCallbacks(
                    onToggleExpanded = callbacks.onToggleExpanded,
                    onEditorCommand = callbacks.onEditorCommand,
                    onToolbarOrderChanged = callbacks.onToolbarOrderChanged,
                    onSubmit = callbacks.onSubmit,
                ),
            benchmarkSubmitTag = benchmarkSubmitTag,
            haptic = haptic,
        )
    }
}

@Composable
private fun InputEditorActionBadgeHost(
    badge: InputEditorActionBadge?,
    onEditorCommand: (InputEditorCommand) -> Unit,
) {
    InputEditorActionBadgeContent(
        badge = badge,
        onClick = {
            if (badge != null) {
                onEditorCommand(badge.command)
            }
        },
    )
}

@Composable
private fun ColumnScope.InputEditorBodyContent(
    state: InputEditorBodyState,
    editorAlpha: Float,
    previewAlpha: Float,
    benchmarkEditorTag: String?,
    onTextChange: (TextFieldValue) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (state.isExpanded) Modifier.weight(1f) else Modifier),
    ) {
        InputEditorTextField(
            enabled = state.editorInteractive,
            state =
                InputEditorTextFieldState(
                    isExpanded = state.isExpanded,
                    showsPlaceholder = state.chromeState.showsPlaceholder,
                    inputValue = state.inputValue,
                    hintText = state.hintText,
                    focusRequester = state.focusRequester,
                    textStyle = state.inputTextStyle,
                    placeholderTextStyle = state.hintTextStyle,
                    benchmarkEditorTag = benchmarkEditorTag,
                ),
            onTextChange = onTextChange,
            modifier =
                if (state.isExpanded) {
                    Modifier
                        .fillMaxSize()
                        .alpha(editorAlpha)
                } else {
                    Modifier.alpha(editorAlpha)
                },
        )
        if (state.chromeState.showsPreviewContent) {
            InputEditorPreviewContent(
                inputText = state.inputValue.text,
                renderState = state.previewState,
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
    surface: InputEditorSurfaceState,
    callbacks: InputEditorToolbarCallbacks,
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
        motionTarget = ::resolveInputEditorFormattingToolbarTransitionState,
    ) { chromeModifier ->
        InputEditorToolbar(
            state =
                InputEditorToolbarState(
                    toggleIcon = chromeState.toggleIcon,
                    isExpanded = isExpanded,
                    isSubmitEnabled = isSubmitEnabled,
                    enabled = chromeState.formattingToolbar.isInteractive,
                    tools = toolbarTools,
                    benchmarkSubmitTag = benchmarkSubmitTag,
                    haptic = haptic,
                ),
            callbacks = callbacks,
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
    motionTarget: (InputSheetPresentationState) -> InputEditorChromeTransitionState,
): InputEditorChromeMotion {
    val scheme = MaterialTheme.motionScheme
    val transition = LocalInputSheetPresentationTransition.current
    val alpha by transition.animateFloat(
        transitionSpec = { scheme.defaultEffectsSpec() },
        label = "InputEditorChromeAlpha",
    ) { if (motionTarget(it).isVisible) 1f else 0f }
    val offsetY by transition.animateDp(
        transitionSpec = { scheme.defaultSpatialSpec() },
        label = "InputEditorChromeOffsetY",
    ) { state ->
        val target = motionTarget(state)
        if (target.isVisible) InputSheetTokens.CollapsedInset else target.hiddenOffsetY
    }
    return InputEditorChromeMotion(alpha, offsetY)
}

@Composable
private fun InputEditorChromeTransitionHost(
    transitionState: InputEditorChromeTransitionState,
    motionTarget: (InputSheetPresentationState) -> InputEditorChromeTransitionState,
    content: @Composable (Modifier) -> Unit,
) {
    if (!transitionState.keepsHostMounted) {
        return
    }

    val chromeMotion = rememberInputEditorChromeMotion(motionTarget)
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
private fun InputEditorTagSelector(
    availableTags: ImmutableList<String>,
    showTagSelector: Boolean,
    slots: InputSheetSlots,
    onTagSelected: (String) -> Unit,
) {
    val motionScheme = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = showTagSelector && availableTags.isNotEmpty(),
        enter =
            expandVertically(
                animationSpec =
                    motionScheme.defaultSpatialSpec(),
            ) +
                fadeIn(
                    animationSpec =
                        motionScheme.defaultEffectsSpec(),
                ),
        exit =
            shrinkVertically(
                animationSpec =
                    motionScheme.defaultSpatialSpec(),
            ) +
                fadeOut(
                    animationSpec =
                        motionScheme.fastEffectsSpec(),
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
