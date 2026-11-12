package com.lomo.ui.component.input

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.AppHapticFeedback
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

data class InputSheetState(val surface: InputEditorSurfaceState, val ownerSubmission: InputSheetOwnerSubmission)

/**
 * The fate of this sheet's in-flight submission, as reported by whoever owns the durable mutation.
 *
 * The sheet's submission lock is a projection of this value. The owner outlives the sheet's
 * composition, so a torn-down acknowledgement path must still release the editor.
 */
enum class InputSheetOwnerSubmission {
    Pending,
    Resolved,
    Rejected,
}

/** Projects [owner] onto the sheet's local submission lock. Never revives an unlocked sheet. */
internal fun applyInputSheetOwnerSubmission(sessionState: InputSheetSessionState, owner: InputSheetOwnerSubmission) {
    if (!sessionState.isSubmitting) return
    when (owner) {
        InputSheetOwnerSubmission.Pending -> Unit

        InputSheetOwnerSubmission.Resolved -> sessionState.clearSubmissionLock()

        InputSheetOwnerSubmission.Rejected -> {
            sessionState.clearSubmissionLock()
            sessionState.isDismissing = false
            sessionState.isSheetVisible = true
        }
    }
}

sealed interface InputInterceptionResult {
    data class UpdateValue(val value: TextFieldValue) : InputInterceptionResult

    data class SubmitContent(val content: String) : InputInterceptionResult
}

fun interface InputInterceptor {
    fun intercept(previousValue: TextFieldValue, newValue: TextFieldValue): InputInterceptionResult
}

private object PassThroughInputInterceptor : InputInterceptor {
    override fun intercept(previousValue: TextFieldValue, newValue: TextFieldValue): InputInterceptionResult =
        InputInterceptionResult.UpdateValue(newValue)
}

fun passThroughInputInterceptor(): InputInterceptor = PassThroughInputInterceptor

data class InputSheetCallbacks(
    val onInputValueChange: (TextFieldValue) -> Unit,
    val onDismiss: () -> Unit,
    /** Commits the long-form target; the sheet animates toward it from wherever its motion is. */
    val onExpandedChange: (Boolean) -> Unit,
    val onDisplayModeChange: (InputEditorDisplayMode) -> Unit,
    val onSubmit: suspend (String) -> Boolean,
    val commands: InputEditorCommandHandler,
    val onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
    val inputInterceptor: InputInterceptor = passThroughInputInterceptor(),
    val autoSubmitOnDismiss: Boolean = false,
    val hasDraftPersistence: Boolean = false,
    /**
     * Fired once per accepted submission after the owner reports a durable commit. Rejected or
     * failed submissions never reach it, so callers can surface a "saved" confirmation here.
     */
    val onAutoSaveCommitted: () -> Unit = {},
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
    benchmarkExpandTag: String? = null,
) {
    val surface = state.surface
    val inputValue = surface.inputValue
    val hintText = remember(surface.hints) { surface.hints.randomOrNull().orEmpty() }
    val layoutMotion = LocalLomoMotion.current.layout
    // One owner moves the sheet between its forms; toggle, back and drag all retarget it in flight.
    val expansion = remember { InputSheetExpansionState(expanded = surface.isExpanded) }
    LaunchedEffect(expansion, surface.isExpanded, layoutMotion) {
        expansion.animateTo(expanded = surface.isExpanded, animationSpec = layoutMotion.defaultSpatialSpec())
    }
    // A compact card always edits: preview needs the long form's height to be useful.
    val displayMode = if (surface.isExpanded) surface.displayMode else InputEditorDisplayMode.Edit
    val displayProgress =
        animateFloatAsState(
            targetValue = if (displayMode == InputEditorDisplayMode.Preview) 1f else 0f,
            animationSpec = layoutMotion.defaultEffectsSpec(),
            label = "InputEditorDisplayProgress",
        )
    val sessionState = rememberInputSheetSessionState(surface.focusRequestToken, inputValue.text)
    val visibilityState = remember { MutableTransitionState(false) }
    visibilityState.targetState = sessionState.isSheetVisible
    LaunchedEffect(visibilityState.isIdle, visibilityState.currentState, sessionState.isDismissing) {
        sessionState.isSheetEntrySettled = visibilityState.isIdle && visibilityState.currentState
        if (sessionState.isDismissing && visibilityState.isIdle && !visibilityState.currentState) {
            callbacks.onDismiss()
        }
    }
    CompositionLocalProvider(LocalInputSheetVisibilityState provides visibilityState) {
        InputSheetSessionContent(
            state = state,
            callbacks = callbacks,
            slots = slots,
            sessionState = sessionState,
            motion =
            InputSheetMotion(
                expansion = expansion,
                displayMode = displayMode,
                displayProgress = displayProgress,
            ),
            hintText = hintText,
            benchmarkTags =
            InputSheetBenchmarkTags(
                root = benchmarkRootTag,
                editor = benchmarkEditorTag,
                submit = benchmarkSubmitTag,
                expand = benchmarkExpandTag,
            ),
        )
    }
}

@Composable
private fun InputSheetSessionContent(
    state: InputSheetState,
    callbacks: InputSheetCallbacks,
    slots: InputSheetSlots,
    sessionState: InputSheetSessionState,
    motion: InputSheetMotion,
    hintText: String,
    benchmarkTags: InputSheetBenchmarkTags,
) {
    val surface = state.surface
    val inputValue = surface.inputValue
    val currentInputValue by rememberUpdatedState(inputValue)
    val haptic = LocalAppHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val focusParkingRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val dismissSheet =
        rememberDismissSheetAction(
            isDismissing = sessionState.isDismissing,
            onDismissingChange = { sessionState.isDismissing = it },
            onSheetVisibleChange = { sessionState.isSheetVisible = it },
            focusParkingRequester = focusParkingRequester,
            keyboardController = keyboardController,
        )
    val submitWithLock =
        rememberSubmitWithLock(
            sessionState = sessionState,
            onSubmit = callbacks.onSubmit,
            onAutoSaveCommitted = callbacks.onAutoSaveCommitted,
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

    val backProgress = com.lomo.ui.component.navigation.rememberPredictiveBackProgress(
        enabled = !sessionState.isDismissing && !sessionState.showDiscardDialog,
    ) {
        if (surface.isExpanded) {
            callbacks.onExpandedChange(false)
        } else {
            requestDismiss()
        }
    }
    CompositionLocalProvider(LocalInputSheetBackProgress provides backProgress) {
        InputSheetLifecycle(
            state =
            InputSheetLifecycleState(
                sessionState = sessionState,
                sheetState = state,
                inputText = inputValue.text,
                editorAcceptsFocus = motion.displayMode == InputEditorDisplayMode.Edit,
                focusRequester = focusRequester,
                focusParkingRequester = focusParkingRequester,
                focusRequestToken = surface.focusRequestToken,
                keyboardController = keyboardController,
            ),

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
            params =
            InputSheetContentParams(
                state = state,
                callbacks = callbacks,
                slots = slots,
                sessionState = sessionState,
                motion = motion,
                inputValue = inputValue,
                hintText = hintText,
                focusRequester = focusRequester,
                focusParkingRequester = focusParkingRequester,
                haptic = haptic,
                dismissSheet = dismissSheet,
                requestDismiss = requestDismiss,
                handleTextChange = handleTextChange,
                submitWithLock = submitWithLock,
                benchmarkTags = benchmarkTags,
            ),
        )
    }
}

@Composable
private fun rememberInputSheetSessionState(sessionToken: Long, initialInputText: String): InputSheetSessionState =
    remember(sessionToken) { InputSheetSessionState(initialInputText) }

internal class InputSheetSessionState(val initialInputText: String) {
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
    onSubmit: suspend (String) -> Boolean,
    onAutoSaveCommitted: () -> Unit,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    focusParkingRequester: FocusRequester,
    scope: kotlinx.coroutines.CoroutineScope,
): (String, String, String) -> Unit =
    remember(sessionState, onSubmit, onAutoSaveCommitted, keyboardController, focusParkingRequester, scope) {
        { content, triggerText, sourceText ->
            submitInputSheetContent(
                sessionState = sessionState,
                content = content,
                triggerText = triggerText,
                sourceText = sourceText,
                scope = scope,
                releaseFocus = {
                    releaseEditorFocusAndKeyboardImmediately(
                        keyboardController = keyboardController,
                        focusParkingRequester = focusParkingRequester,
                    )
                },
                onSubmit = onSubmit,
                onCommitted = onAutoSaveCommitted,
            )
        }
    }

/** Accepts one send intent and starts its state transition before presentation cleanup can recompose. */
internal fun submitInputSheetContent(
    sessionState: InputSheetSessionState,
    content: String,
    triggerText: String,
    sourceText: String,
    scope: kotlinx.coroutines.CoroutineScope,
    releaseFocus: () -> Unit,
    onSubmit: suspend (String) -> Boolean,
    onCommitted: () -> Unit = {},
): Boolean {
    if (sessionState.isSubmitting) return false
    sessionState.isSubmitting = true
    sessionState.pendingSubmissionTriggerText = triggerText
    sessionState.submissionLockSourceText = sourceText
    sessionState.isDismissing = true
    sessionState.isSheetVisible = false
    releaseFocus()
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var committed = false
        try {
            committed = onSubmit(content)
        } finally {
            if (committed) {
                onCommitted()
            } else {
                sessionState.clearSubmissionLock()
                sessionState.isDismissing = false
                sessionState.isSheetVisible = true
            }
        }
    }
    return true
}

@Composable
private fun rememberRequestDismiss(
    sessionState: InputSheetSessionState,
    currentInputText: String,
    autoSubmitOnDismiss: Boolean,
    hasDraftPersistence: Boolean,
    dismissSheet: () -> Unit,
    submitWithLock: (String, String, String) -> Unit,
): () -> Unit = remember(
    sessionState,
    currentInputText,
    autoSubmitOnDismiss,
    hasDraftPersistence,
    dismissSheet,
    submitWithLock,
) {
    requestDismiss@{
        if (sessionState.isSubmitting) return@requestDismiss
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
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
): () -> Unit = dismiss@{
    if (isDismissing) return@dismiss
    onDismissingChange(true)
    releaseEditorFocusAndKeyboardImmediately(
        keyboardController = keyboardController,
        focusParkingRequester = focusParkingRequester,
    )
    onSheetVisibleChange(false)
}

@Composable
private fun InputSheetLifecycle(state: InputSheetLifecycleState) {
    InputSheetVisibilityEffects(
        focusRequestToken = state.focusRequestToken,
        onSheetVisibleChange = { state.sessionState.isSheetVisible = it },
    )
    InputSheetFocusRequestEffects(
        state =
        InputSheetFocusRequestState(
            sessionState = state.sessionState,
            editorAcceptsFocus = state.editorAcceptsFocus,
            isRecording = state.sheetState.surface.recordingState.isRecording,
            focusRequester = state.focusRequester,
            focusParkingRequester = state.focusParkingRequester,
            focusRequestToken = state.focusRequestToken,
            keyboardController = state.keyboardController,
        ),
    )
    InputSheetSubmissionResetEffect(
        inputText = state.inputText,
        isSubmitting = state.sessionState.isSubmitting,
        submissionLockSourceText = state.sessionState.submissionLockSourceText,
        onClearSubmissionLock = state.sessionState::clearSubmissionLock,
    )
    // The lock is a projection of the owner: an acknowledgement path that died with its scope
    // must never be the only way out of the submitting state.
    LaunchedEffect(state.sheetState.ownerSubmission, state.sessionState.isSubmitting) {
        applyInputSheetOwnerSubmission(state.sessionState, state.sheetState.ownerSubmission)
    }
}
