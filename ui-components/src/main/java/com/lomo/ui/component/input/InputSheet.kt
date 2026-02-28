package com.lomo.ui.component.input

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import com.lomo.ui.R
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.util.AppHapticFeedback
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class InputSheetState(
    val inputValue: TextFieldValue,
    val availableTags: List<String> = emptyList(),
    val isRecording: Boolean = false,
    val recordingDuration: Long = 0L,
    val recordingAmplitude: Int = 0,
    val hints: List<String> = emptyList(),
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

object TripleEnterSubmitInterceptor : InputInterceptor {
    override fun intercept(
        previousValue: TextFieldValue,
        newValue: TextFieldValue,
    ): InputInterceptionResult {
        val oldText = previousValue.text
        val newText = newValue.text
        val shouldSubmit = newText.length > oldText.length && newText.endsWith("\n\n\n")
        return if (shouldSubmit) {
            InputInterceptionResult.SubmitContent(newText.trim())
        } else {
            InputInterceptionResult.UpdateValue(newValue)
        }
    }
}

data class InputSheetCallbacks(
    val onInputValueChange: (TextFieldValue) -> Unit,
    val onDismiss: () -> Unit,
    val onSubmit: (String) -> Unit,
    val onImageClick: () -> Unit,
    val onCameraClick: () -> Unit = {},
    val onStartRecording: () -> Unit = {},
    val onStopRecording: () -> Unit = {},
    val onCancelRecording: () -> Unit = {},
    val inputInterceptor: InputInterceptor = TripleEnterSubmitInterceptor,
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
) {
    val inputValue = state.inputValue
    val availableTags = state.availableTags
    val isRecording = state.isRecording
    val recordingDuration = state.recordingDuration
    val recordingAmplitude = state.recordingAmplitude
    val hints = state.hints

    val onInputValueChange = callbacks.onInputValueChange
    val onDismiss = callbacks.onDismiss
    val onSubmit = callbacks.onSubmit
    val onImageClick = callbacks.onImageClick
    val onCameraClick = callbacks.onCameraClick
    val onStartRecording = callbacks.onStartRecording
    val onStopRecording = callbacks.onStopRecording
    val onCancelRecording = callbacks.onCancelRecording
    val inputInterceptor = callbacks.inputInterceptor

    var showTagSelector by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var pendingSubmissionTriggerText by remember { mutableStateOf<String?>(null) }
    var submissionLockSourceText by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val hintText = remember(hints) { hints.randomOrNull().orEmpty() }
    val currentInputValue by rememberUpdatedState(inputValue)

    val haptic = LocalAppHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var isSheetVisible by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }

    val dismissSheet: () -> Unit = dismiss@{
        if (isDismissing) return@dismiss
        isDismissing = true
        scope.launch {
            isSheetVisible = false
            keyboardController?.hide()
            // Keep panel visible briefly so it rides with IME hide animation.
            delay(MotionTokens.DurationLong2.toLong())
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        isSheetVisible = true
    }

    LaunchedEffect(isSheetVisible, isRecording, isDismissing) {
        if (!isSheetVisible || isDismissing) return@LaunchedEffect

        if (isRecording) {
            keyboardController?.hide()
            return@LaunchedEffect
        }

        withFrameNanos { }
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val requestDismiss: () -> Unit = {
        if (currentInputValue.text.isNotBlank()) {
            showDiscardDialog = true
        } else {
            dismissSheet()
        }
    }

    BackHandler(enabled = true) {
        requestDismiss()
    }

    val clearSubmissionLock = {
        isSubmitting = false
        pendingSubmissionTriggerText = null
        submissionLockSourceText = null
    }

    val submitWithLock: (content: String, triggerText: String, sourceText: String) -> Unit = submit@{ content, triggerText, sourceText ->
        if (isSubmitting && pendingSubmissionTriggerText == triggerText) {
            return@submit
        }
        isSubmitting = true
        pendingSubmissionTriggerText = triggerText
        submissionLockSourceText = sourceText
        onSubmit(content)
    }

    LaunchedEffect(inputValue.text, isSubmitting, submissionLockSourceText) {
        val sourceText = submissionLockSourceText ?: return@LaunchedEffect
        if (isSubmitting && inputValue.text != sourceText) {
            clearSubmissionLock()
        }
    }

    if (showDiscardDialog) {
        InputDiscardDialog(
            onDismiss = { showDiscardDialog = false },
            onConfirmDiscard = {
                showDiscardDialog = false
                dismissSheet()
            },
        )
    }

    val handleTextChange: (TextFieldValue) -> Unit = handleTextChange@{ newValue ->
        if (isSubmitting) {
            if (newValue.text == pendingSubmissionTriggerText) {
                return@handleTextChange
            }
            clearSubmissionLock()
        }

        when (val interception = inputInterceptor.intercept(inputValue, newValue)) {
            is InputInterceptionResult.SubmitContent -> {
                haptic.heavy()
                submitWithLock(interception.content, newValue.text, inputValue.text)
            }

            is InputInterceptionResult.UpdateValue -> {
                onInputValueChange(interception.value)
            }
        }
    }

    InputSheetScaffold(
        isSheetVisible = isSheetVisible,
        scrimAlpha = if (isSheetVisible || isDismissing) 0.32f else 0f,
        onRequestDismiss = requestDismiss,
    ) {
        AnimatedContent(
            targetState = isRecording,
            transitionSpec = { fadeScaleContentTransition() },
            label = "RecordingStateTransition",
        ) { recording ->
            if (recording) {
                slots.voiceRecordingPanel(
                    VoiceRecordingPanelState(
                        recordingDuration = recordingDuration,
                        recordingAmplitude = recordingAmplitude,
                    ),
                    VoiceRecordingPanelCallbacks(
                        onCancel = {
                            haptic.medium()
                            onCancelRecording()
                        },
                        onStop = {
                            haptic.heavy()
                            onStopRecording()
                        },
                    ),
                )
            } else {
                InputEditorPanel(
                    inputValue = inputValue,
                    hintText = hintText,
                    availableTags = availableTags,
                    showTagSelector = showTagSelector,
                    focusRequester = focusRequester,
                    onTextChange = handleTextChange,
                    onTagSelected = { tag ->
                        haptic.medium()
                        val newText = "${inputValue.text} #$tag "
                        onInputValueChange(TextFieldValue(newText, TextRange(newText.length)))
                        showTagSelector = false
                    },
                    onToggleTagSelector = { showTagSelector = !showTagSelector },
                    onCameraClick = onCameraClick,
                    onImageClick = onImageClick,
                    onStartRecording = onStartRecording,
                    onInsertTodo = {
                        val start = inputValue.text
                        val newText = if (start.isEmpty()) "- [ ] " else "$start\n- [ ] "
                        onInputValueChange(TextFieldValue(newText, TextRange(newText.length)))
                    },
                    onSubmit = {
                        if (inputValue.text.isNotBlank()) {
                            submitWithLock(inputValue.text.trim(), inputValue.text, inputValue.text)
                        }
                    },
                    slots = slots,
                    haptic = haptic,
                )
            }
        }
    }
}

@Composable
private fun InputDiscardDialog(
    onDismiss: () -> Unit,
    onConfirmDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.input_discard_title)) },
        text = { Text(stringResource(R.string.input_discard_message)) },
        confirmButton = {
            TextButton(onClick = onConfirmDiscard) {
                Text(stringResource(R.string.input_discard_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.input_discard_cancel))
            }
        },
    )
}

@Composable
private fun InputSheetScaffold(
    isSheetVisible: Boolean,
    scrimAlpha: Float,
    onRequestDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onRequestDismiss() })
                    },
        ) {
            // consume
        }

        AnimatedVisibility(
            visible = isSheetVisible,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding(),
            enter =
                slideInVertically(
                    initialOffsetY = { height -> height },
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = MotionTokens.DurationLong2,
                            easing = MotionTokens.EasingStandard,
                        ),
                ),
            exit =
                slideOutVertically(
                    targetOffsetY = { height -> height },
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = MotionTokens.DurationLong2,
                            easing = MotionTokens.EasingStandard,
                        ),
                ),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(AppShapes.ExtraLarge)
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { /* consume */ })
                        },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    InputSheetDragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
                    content()
                }
            }
        }
    }
}

@Composable
private fun InputSheetDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .padding(vertical = 22.dp)
                .width(32.dp)
                .height(4.dp)
                .clip(AppShapes.ExtraSmall)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                .clearAndSetSemantics { },
    )
}

@Composable
private fun InputEditorPanel(
    inputValue: TextFieldValue,
    hintText: String,
    availableTags: List<String>,
    showTagSelector: Boolean,
    focusRequester: FocusRequester,
    onTextChange: (TextFieldValue) -> Unit,
    onTagSelected: (String) -> Unit,
    onToggleTagSelector: () -> Unit,
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onStartRecording: () -> Unit,
    onInsertTodo: () -> Unit,
    onSubmit: () -> Unit,
    slots: InputSheetSlots,
    haptic: AppHapticFeedback,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Medium),
    ) {
        val bodyLargeStyle = MaterialTheme.typography.bodyLarge
        val inputTextStyle = remember(inputValue.text, bodyLargeStyle) { bodyLargeStyle.scriptAwareFor(inputValue.text) }

        TextField(
            value = inputValue,
            onValueChange = onTextChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            minLines = 3,
            maxLines = 10,
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            shape = AppShapes.Large,
            textStyle = inputTextStyle,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(),
            placeholder = { InputHintPlaceholder(hintText = hintText) },
        )

        Spacer(modifier = Modifier.height(AppSpacing.MediumSmall))

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
            slots.tagSelectorBar(
                TagSelectorBarState(availableTags = availableTags),
                TagSelectorBarCallbacks(onTagSelected = onTagSelected),
            )
        }

        InputEditorToolbar(
            showTagSelector = showTagSelector,
            isSubmitEnabled = inputValue.text.isNotBlank(),
            onCameraClick = onCameraClick,
            onImageClick = onImageClick,
            onStartRecording = onStartRecording,
            onToggleTagSelector = onToggleTagSelector,
            onInsertTodo = onInsertTodo,
            onSubmit = onSubmit,
            haptic = haptic,
        )
    }
}

@Composable
private fun InputEditorToolbar(
    showTagSelector: Boolean,
    isSubmitEnabled: Boolean,
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onStartRecording: () -> Unit,
    onToggleTagSelector: () -> Unit,
    onInsertTodo: () -> Unit,
    onSubmit: () -> Unit,
    haptic: AppHapticFeedback,
    modifier: Modifier = Modifier,
) {
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                onStartRecording()
            }
        }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                haptic.medium()
                onCameraClick()
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.PhotoCamera,
                contentDescription = stringResource(R.string.cd_take_photo),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(
            onClick = {
                haptic.medium()
                onImageClick()
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.Image,
                contentDescription = stringResource(R.string.cd_add_image),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(
            onClick = {
                haptic.medium()
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = stringResource(R.string.cd_add_voice_memo),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(
            onClick = {
                haptic.medium()
                onToggleTagSelector()
            },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Label,
                contentDescription = stringResource(R.string.cd_add_tag),
                tint =
                    if (showTagSelector) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }

        IconButton(
            onClick = {
                haptic.medium()
                onInsertTodo()
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckBox,
                contentDescription = stringResource(R.string.cd_add_checkbox),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        FilledTonalButton(
            onClick = {
                haptic.heavy()
                onSubmit()
            },
            enabled = isSubmitEnabled,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = stringResource(R.string.cd_send),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun InputHintPlaceholder(hintText: String) {
    if (hintText.isEmpty()) return

    AnimatedContent(
        targetState = hintText,
        transitionSpec = { fadeScaleContentTransition() },
        label = "HintAnimation",
    ) { targetHint ->
        Text(
            text = targetHint,
            style = MaterialTheme.typography.bodyLarge.scriptAwareFor(targetHint),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

private fun fadeScaleContentTransition(): ContentTransform =
    (
        fadeIn(animationSpec = androidx.compose.animation.core.tween(MotionTokens.DurationMedium2)) +
            scaleIn(
                initialScale = 0.95f,
                animationSpec =
                    androidx.compose.animation.core.tween(
                        MotionTokens.DurationMedium2,
                        easing = MotionTokens.EasingEmphasizedDecelerate,
                    ),
            )
    ).togetherWith(
        fadeOut(
            animationSpec = androidx.compose.animation.core.tween(durationMillis = MotionTokens.DurationShort4),
        ),
    )
