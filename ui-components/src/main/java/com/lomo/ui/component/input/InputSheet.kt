package com.lomo.ui.component.input

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.lomo.ui.R
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.util.AppHapticFeedback
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val INPUT_SHEET_DISMISS_KEYBOARD_DELAY_MILLIS = 150L

data class InputSheetState(
    val inputValue: TextFieldValue,
    val previewContent: String? = null,
    val focusRequestToken: Long = 0L,
    val isExpanded: Boolean = false,
    val displayMode: InputEditorDisplayMode = InputEditorDisplayMode.Edit,
    val availableTags: ImmutableList<String> = persistentListOf(),
    val isRecording: Boolean = false,
    val recordingDuration: Long = 0L,
    val recordingAmplitude: Int = 0,
    val hints: ImmutableList<String> = persistentListOf(),
)

sealed interface InputInterceptionResult {
    data class UpdateValue(
        val value: TextFieldValue,
    ) : InputInterceptionResult

    data class SubmitContent(
        val content: String,
    ) : InputInterceptionResult
}

fun interface InputInterceptor {
    fun intercept(
        previousValue: TextFieldValue,
        newValue: TextFieldValue,
    ): InputInterceptionResult
}

private object PassThroughInputInterceptor : InputInterceptor {
    override fun intercept(
        previousValue: TextFieldValue,
        newValue: TextFieldValue,
    ): InputInterceptionResult = InputInterceptionResult.UpdateValue(newValue)
}

fun passThroughInputInterceptor(): InputInterceptor = PassThroughInputInterceptor

data class InputSheetCallbacks(
    val onInputValueChange: (TextFieldValue) -> Unit,
    val onDismiss: () -> Unit,
    val onToggleExpanded: () -> Unit = {},
    val onCollapse: () -> Unit = {},
    val onDisplayModeChange: (InputEditorDisplayMode) -> Unit = {},
    val onConsumeBackPress: () -> Boolean = { false },
    val onSubmit: (String) -> Unit,
    val onImageClick: () -> Unit,
    val onCameraClick: () -> Unit = {},
    val onStartRecording: () -> Unit = {},
    val onStopRecording: () -> Unit = {},
    val onCancelRecording: () -> Unit = {},
    val inputInterceptor: InputInterceptor = passThroughInputInterceptor(),
    val autoSubmitOnDismiss: Boolean = false,
    val hasDraftPersistence: Boolean = false,
)

data class InputSheetSlots(
    val voiceRecordingPanel: @Composable (VoiceRecordingPanelState, VoiceRecordingPanelCallbacks) -> Unit =
        { state, callbacks ->
            VoiceRecordingPanel(state = state, callbacks = callbacks)
        },
    val tagSelectorBar: @Composable (TagSelectorBarState, TagSelectorBarCallbacks) -> Unit =
        { state, callbacks ->
            TagSelectorBar(state = state, callbacks = callbacks)
        },
)

@Composable
fun InputSheet(
    state: InputSheetState,
    callbacks: InputSheetCallbacks,
    slots: InputSheetSlots = InputSheetSlots(),
    benchmarkRootTag: String? = null,
    benchmarkEditorTag: String? = null,
    benchmarkSubmitTag: String? = null,
) {
    val inputValue = state.inputValue
    val hintText = remember(state.hints) { state.hints.randomOrNull().orEmpty() }
    val resolvedDisplayMode =
        if (state.isExpanded) {
            state.displayMode
        } else {
            InputEditorDisplayMode.Edit
        }
    val presentationState =
        rememberInputSheetPresentationState(
            targetExpanded = state.isExpanded,
            targetDisplayMode = resolvedDisplayMode,
        )
    val currentInputValue by rememberUpdatedState(inputValue)
    val sessionState = rememberInputSheetSessionState(inputValue.text)
    val haptic = LocalAppHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val focusParkingRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var editorView by remember { mutableStateOf<MemoInputEditText?>(null) }
    val scope = rememberCoroutineScope()
    val dismissSheet =
        rememberDismissSheetAction(
            isDismissing = sessionState.isDismissing,
            onDismissingChange = { sessionState.isDismissing = it },
            onSheetVisibleChange = { sessionState.isSheetVisible = it },
            focusParkingRequester = focusParkingRequester,
            editorView = editorView,
            keyboardController = keyboardController,
            scope = scope,
            onDismiss = callbacks.onDismiss,
        )
    val submitWithLock =
        rememberSubmitWithLock(
            sessionState = sessionState,
            onSubmit = callbacks.onSubmit,
            editorView = editorView,
            keyboardController = keyboardController,
            focusParkingRequester = focusParkingRequester,
            scope = scope,
        )
    val requestDismiss =
        rememberRequestDismiss(
            sessionState = sessionState,
            currentInputText = currentInputValue.text,
            autoSubmitOnDismiss = callbacks.autoSubmitOnDismiss,
            hasDraftPersistence = callbacks.hasDraftPersistence,
            dismissSheet = dismissSheet,
            submitWithLock = submitWithLock,
        )

    InputSheetLifecycle(
        sessionState = sessionState,
        state = state,
        inputText = inputValue.text,
        presentationState = presentationState,
        focusRequester = focusRequester,
        focusParkingRequester = focusParkingRequester,
        focusRequestToken = state.focusRequestToken,
        editorView = editorView,
        keyboardController = keyboardController,
        onCollapse = callbacks.onCollapse,
        onConsumeBackPress = callbacks.onConsumeBackPress,
        onRequestDismiss = requestDismiss,
    )

    val handleTextChange =
        rememberInputTextChangeHandler(
            sessionState = sessionState,
            currentValue = inputValue,
            inputInterceptor = callbacks.inputInterceptor,
            onInputValueChange = callbacks.onInputValueChange,
            submitWithLock = submitWithLock,
            haptic = haptic,
        )

    InputSheetContent(
        state = state,
        callbacks = callbacks,
        slots = slots,
        sessionState = sessionState,
        presentationState = presentationState,
        inputValue = inputValue,
        previewContent = state.previewContent,
        hintText = hintText,
        focusRequester = focusRequester,
        focusParkingRequester = focusParkingRequester,
        onEditorReady = { editorView = it },
        haptic = haptic,
        dismissSheet = dismissSheet,
        requestDismiss = requestDismiss,
        handleTextChange = handleTextChange,
        submitWithLock = submitWithLock,
        benchmarkRootTag = benchmarkRootTag,
        benchmarkEditorTag = benchmarkEditorTag,
        benchmarkSubmitTag = benchmarkSubmitTag,
    )
}

@Composable
private fun rememberInputSheetSessionState(initialInputText: String): InputSheetSessionState =
    remember { InputSheetSessionState(initialInputText) }

internal class InputSheetSessionState(
    val initialInputText: String,
) {
    var showTagSelector by mutableStateOf(false)
    var isSubmitting by mutableStateOf(false)
    var pendingSubmissionTriggerText by mutableStateOf<String?>(null)
    var submissionLockSourceText by mutableStateOf<String?>(null)
    var showDiscardDialog by mutableStateOf(false)
    var isSheetVisible by mutableStateOf(false)
    var isSheetEntrySettled by mutableStateOf(false)
    var isDismissing by mutableStateOf(false)

    fun clearSubmissionLock() {
        isSubmitting = false
        pendingSubmissionTriggerText = null
        submissionLockSourceText = null
    }
}

@Composable
private fun rememberSubmitWithLock(
    sessionState: InputSheetSessionState,
    onSubmit: (String) -> Unit,
    editorView: MemoInputEditText?,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    focusParkingRequester: FocusRequester,
    scope: kotlinx.coroutines.CoroutineScope,
): (String, String, String) -> Unit =
    remember(sessionState, onSubmit, editorView, keyboardController, focusParkingRequester, scope) {
        submit@{ content, triggerText, sourceText ->
            if (sessionState.isSubmitting && sessionState.pendingSubmissionTriggerText == triggerText) {
                return@submit
            }
            sessionState.isSubmitting = true
            sessionState.pendingSubmissionTriggerText = triggerText
            sessionState.submissionLockSourceText = sourceText
            releaseEditorFocusAndKeyboardImmediately(
                editor = editorView,
                keyboardController = keyboardController,
                focusParkingRequester = focusParkingRequester,
            )
            scope.launch {
                delay(INPUT_SHEET_DISMISS_KEYBOARD_DELAY_MILLIS)
                withFrameNanos { }
                onSubmit(content)
            }
        }
    }

@Composable
private fun rememberRequestDismiss(
    sessionState: InputSheetSessionState,
    currentInputText: String,
    autoSubmitOnDismiss: Boolean,
    hasDraftPersistence: Boolean,
    dismissSheet: () -> Unit,
    submitWithLock: (String, String, String) -> Unit,
): () -> Unit =
    remember(
        sessionState,
        currentInputText,
        autoSubmitOnDismiss,
        hasDraftPersistence,
        dismissSheet,
        submitWithLock,
    ) {
        {
            val hasUnsavedChanges = currentInputText != sessionState.initialInputText
            when {
                autoSubmitOnDismiss && currentInputText.isNotBlank() -> {
                    submitWithLock(currentInputText.trim(), currentInputText, currentInputText)
                }

                hasDraftPersistence -> dismissSheet()
                hasUnsavedChanges -> sessionState.showDiscardDialog = true
                else -> dismissSheet()
            }
        }
    }

@Composable
private fun rememberInputTextChangeHandler(
    sessionState: InputSheetSessionState,
    currentValue: TextFieldValue,
    inputInterceptor: InputInterceptor,
    onInputValueChange: (TextFieldValue) -> Unit,
    submitWithLock: (String, String, String) -> Unit,
    haptic: AppHapticFeedback,
): (TextFieldValue) -> Unit =
    remember(sessionState, currentValue, inputInterceptor, onInputValueChange, submitWithLock, haptic) {
        handler@{ newValue ->
            if (sessionState.isSubmitting) {
                if (newValue.text == sessionState.pendingSubmissionTriggerText) {
                    return@handler
                }
                sessionState.clearSubmissionLock()
            }

            when (val interception = inputInterceptor.intercept(currentValue, newValue)) {
                is InputInterceptionResult.SubmitContent -> {
                    haptic.heavy()
                    submitWithLock(interception.content, newValue.text, currentValue.text)
                }

                is InputInterceptionResult.UpdateValue -> {
                    onInputValueChange(interception.value)
                }
            }
        }
    }

@Composable
private fun rememberDismissSheetAction(
    isDismissing: Boolean,
    onDismissingChange: (Boolean) -> Unit,
    onSheetVisibleChange: (Boolean) -> Unit,
    focusParkingRequester: FocusRequester,
    editorView: MemoInputEditText?,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
): () -> Unit =
    dismiss@{
        if (isDismissing) return@dismiss
        onDismissingChange(true)
        releaseEditorFocusAndKeyboardImmediately(
            editor = editorView,
            keyboardController = keyboardController,
            focusParkingRequester = focusParkingRequester,
        )
        scope.launch {
            delay(INPUT_SHEET_DISMISS_KEYBOARD_DELAY_MILLIS)
            onSheetVisibleChange(false)
            delay(MotionTokens.DurationLong2.toLong())
            onDismiss()
        }
    }

@Composable
private fun InputSheetLifecycle(
    sessionState: InputSheetSessionState,
    state: InputSheetState,
    inputText: String,
    presentationState: InputSheetPresentationState,
    focusRequester: FocusRequester,
    focusParkingRequester: FocusRequester,
    focusRequestToken: Long,
    editorView: MemoInputEditText?,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    onCollapse: () -> Unit,
    onConsumeBackPress: () -> Boolean,
    onRequestDismiss: () -> Unit,
) {
    InputSheetVisibilityEffects(
        isSheetVisible = sessionState.isSheetVisible,
        isRecording = state.isRecording,
        isDismissing = sessionState.isDismissing,
        onSheetVisibleChange = { sessionState.isSheetVisible = it },
        onSheetEntrySettledChange = { sessionState.isSheetEntrySettled = it },
    )
    InputSheetFocusRequestEffects(
        isSheetVisible = sessionState.isSheetVisible,
        isSheetEntrySettled = sessionState.isSheetEntrySettled,
        presentationState = presentationState,
        isRecording = state.isRecording,
        isDismissing = sessionState.isDismissing,
        focusRequester = focusRequester,
        focusParkingRequester = focusParkingRequester,
        focusRequestToken = focusRequestToken,
        editorView = editorView,
        keyboardController = keyboardController,
    )
    BackHandler(enabled = true) {
        if (state.isExpanded) {
            onCollapse()
        } else if (!onConsumeBackPress()) {
            onRequestDismiss()
        }
    }
    InputSheetSubmissionResetEffect(
        inputText = inputText,
        isSubmitting = sessionState.isSubmitting,
        submissionLockSourceText = sessionState.submissionLockSourceText,
        onClearSubmissionLock = sessionState::clearSubmissionLock,
    )
}
