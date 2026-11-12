package com.lomo.ui.component.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList

@Composable
internal fun InputEditorFooter(
    presentation: InputEditorPanelState,
    callbacks: InputEditorPanelCallbacks,
    chrome: InputEditorChrome,
    toolbarTools: ImmutableList<InputToolbarTool>,
    benchmarkSubmitTag: String?,
    haptic: AppHapticFeedback,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        InputEditorTagSelector(
            availableTags = presentation.availableTags,
            showTagSelector = presentation.showTagSelector && chrome.formattingInteractive,
            slots = presentation.slots,
            onTagSelected = callbacks.onTagSelected,
        )
        InputEditorToolbar(
            state =
            InputEditorToolbarState(
                isSubmitEnabled = presentation.inputValue.text.isNotBlank(),
                enabled = chrome.formattingInteractive,
                tools = toolbarTools,
                benchmarkSubmitTag = benchmarkSubmitTag,
                haptic = haptic,
            ),
            callbacks =
            InputEditorToolbarCallbacks(
                onEditorCommand = callbacks.onEditorCommand,
                onToolbarOrderChanged = callbacks.onToolbarOrderChanged,
                onSubmit = callbacks.onSubmit,
            ),
            modifier =
            Modifier
                .padding(top = InputSheetTokens.ToolbarTopPadding)
                .graphicsLayer {
                    val preview = presentation.motion.displayProgress.value
                    alpha = 1f - preview
                    translationY = InputSheetTokens.ToolbarPreviewOffset.toPx() * preview
                }.then(
                    if (chrome.formattingInteractive) Modifier else Modifier.clearAndSetSemantics { },
                ),
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
    val motionScheme = LocalLomoMotion.current.layout
    AnimatedVisibility(
        visible = showTagSelector && availableTags.isNotEmpty(),
        enter =
        expandVertically(animationSpec = motionScheme.defaultSpatialSpec()) +
            fadeIn(animationSpec = motionScheme.defaultEffectsSpec()),
        exit =
        shrinkVertically(animationSpec = motionScheme.defaultSpatialSpec()) +
            fadeOut(animationSpec = motionScheme.fastEffectsSpec()),
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
