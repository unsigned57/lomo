package com.lomo.ui.component.input

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.MotionTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputSheet(
    inputValue: TextFieldValue,
    onInputValueChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onImageClick: () -> Unit,
    availableTags: List<String> = emptyList(),
    // Recording params
    isRecording: Boolean = false,
    recordingDuration: Long = 0L,
    recordingAmplitude: Int = 0,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
) {
    var showTagSelector by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Track content state for dismiss protection without causing recomposition
    val currentInputValue by androidx.compose.runtime.rememberUpdatedState(inputValue)

    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    // Discard confirmation dialog
    if (showDiscardDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = {
                Text(
                    androidx.compose.ui.res
                        .stringResource(com.lomo.ui.R.string.input_discard_title),
                )
            },
            text = {
                Text(
                    androidx.compose.ui.res
                        .stringResource(com.lomo.ui.R.string.input_discard_message),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onDismiss()
                    },
                ) {
                    Text(
                        androidx.compose.ui.res
                            .stringResource(com.lomo.ui.R.string.input_discard_confirm),
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showDiscardDialog = false },
                ) {
                    Text(
                        androidx.compose.ui.res
                            .stringResource(com.lomo.ui.R.string.input_discard_cancel),
                    )
                }
            },
        )
    }

    // Smart Enter handler: Triple-Enter (creates 2 empty lines) to send
    // This allows Double-Enter (1 empty line) to be used for formatting (e.g. quotes)
    val handleTextChange: (TextFieldValue) -> Unit = { newValue ->
        if (!isSubmitting) {
            val oldText = inputValue.text
            val newText = newValue.text

            // Detect if user is inserting newlines at the end
            // We check for \n\n\n which means: Text + Newline + EmptyLine + TriggerNewline
            if (newText.length > oldText.length && newText.endsWith("\n\n\n")) {
                // Triple Enter detected -> Send
                isSubmitting = true
                haptic.heavy()
                onSubmit(newText.trim())
            } else {
                onInputValueChange(newValue)
            }
        }
    }

    // Use confirmValueChange to intercept dismiss and show dialog
    val sheetStateWithProtection =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { newValue ->
                if (newValue == androidx.compose.material3.SheetValue.Hidden && currentInputValue.text.isNotBlank()) {
                    // Block hide and show dialog instead
                    showDiscardDialog = true
                    false // Prevent state change
                } else {
                    true // Allow state change
                }
            },
        )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetStateWithProtection,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        // Custom drag handle - consume long-press to prevent system tooltip
        dragHandle = {
            Box(
                modifier =
                    Modifier
                        .padding(vertical = 22.dp)
                        .width(32.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        .clearAndSetSemantics { }
                        // Consume long-press gesture to prevent tooltip
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { /* consume, do nothing */ },
                            )
                        },
            )
        },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .imePadding(),
        ) {
            AnimatedContent(
                targetState = isRecording,
                transitionSpec = {
                    if (targetState) {
                        (
                            fadeIn(animationSpec = tween(MotionTokens.DurationMedium2)) +
                                scaleIn(
                                    initialScale = 0.95f,
                                    animationSpec =
                                        tween(
                                            MotionTokens.DurationMedium2,
                                            easing = MotionTokens.EasingEmphasizedDecelerate,
                                        ),
                                )
                        ).togetherWith(fadeOut(animationSpec = tween(MotionTokens.DurationShort4)))
                    } else {
                        (
                            fadeIn(animationSpec = tween(MotionTokens.DurationMedium2)) +
                                scaleIn(
                                    initialScale = 0.95f,
                                    animationSpec =
                                        tween(
                                            MotionTokens.DurationMedium2,
                                            easing = MotionTokens.EasingEmphasizedDecelerate,
                                        ),
                                )
                        ).togetherWith(fadeOut(animationSpec = tween(MotionTokens.DurationShort4)))
                    }
                },
                label = "RecordingStateTransition",
            ) { recording ->
                if (recording) {
                    // Recording UI
                    Column(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text =
                                androidx.compose.ui.res
                                    .stringResource(com.lomo.ui.R.string.recording_in_progress),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Timer
                        val minutes = (recordingDuration / 1000) / 60
                        val seconds = (recordingDuration / 1000) % 60
                        Text(
                            text = String.format("%02d:%02d", minutes, seconds),
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Visualizer (Simple placeholder for now)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        RoundedCornerShape(12.dp),
                                    ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            // Dynamic waves based on amplitude could go here
                            Box(
                                modifier =
                                    Modifier
                                        .width(
                                            (50 + (recordingAmplitude.coerceIn(0, 32767) / 32767f) * 200).dp,
                                        ).height(4.dp)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Cancel
                            IconButton(
                                onClick = {
                                    haptic.medium()
                                    onCancelRecording()
                                },
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription =
                                        androidx.compose.ui.res.stringResource(
                                            com.lomo.ui.R.string.cd_cancel_recording,
                                        ),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(32.dp),
                                )
                            }

                            // Stop/Confirm
                            androidx.compose.material3.FilledIconButton(
                                onClick = {
                                    haptic.heavy()
                                    onStopRecording()
                                },
                                modifier = Modifier.size(72.dp),
                                colors =
                                    androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                    ),
                            ) {
                                Icon(
                                    Icons.Rounded.Stop,
                                    contentDescription =
                                        androidx.compose.ui.res.stringResource(
                                            com.lomo.ui.R.string.cd_stop_recording,
                                        ),
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(20.dp),
                    ) {
                        // Text input - taller default height
                        TextField(
                            value = inputValue,
                            onValueChange = handleTextChange,
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 10,
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor =
                                        MaterialTheme.colorScheme
                                            .surfaceContainerHigh,
                                    unfocusedContainerColor =
                                        MaterialTheme.colorScheme
                                            .surfaceContainerHigh,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                            shape = RoundedCornerShape(16.dp),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            keyboardOptions =
                                KeyboardOptions(
                                    imeAction = ImeAction.Default,
                                ),
                            keyboardActions = KeyboardActions(),
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Tag Selector
                        AnimatedVisibility(
                            visible = showTagSelector && availableTags.isNotEmpty(),
                            enter =
                                androidx.compose.animation.expandVertically(
                                    animationSpec =
                                        tween(
                                            durationMillis = MotionTokens.DurationMedium2,
                                            easing = MotionTokens.EasingEmphasized,
                                        ),
                                ) +
                                    androidx.compose.animation.fadeIn(
                                        animationSpec =
                                            tween(
                                                durationMillis = MotionTokens.DurationMedium2,
                                            ),
                                    ),
                            exit =
                                androidx.compose.animation.shrinkVertically(
                                    animationSpec =
                                        tween(
                                            durationMillis = MotionTokens.DurationMedium2,
                                            easing = MotionTokens.EasingEmphasized,
                                        ),
                                ) +
                                    androidx.compose.animation.fadeOut(
                                        animationSpec =
                                            tween(
                                                durationMillis = MotionTokens.DurationShort4,
                                            ),
                                    ),
                        ) {
                            Column {
                                Text(
                                    androidx.compose.ui.res.stringResource(
                                        com.lomo.ui.R.string.input_select_tag,
                                    ),
                                    style =
                                        MaterialTheme.typography
                                            .labelMedium,
                                    color =
                                        MaterialTheme.colorScheme
                                            .onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                                LazyRow(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp),
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(32.dp),
                                ) {
                                    items(availableTags) { tag ->
                                        FilterChip(
                                            selected = false,
                                            onClick = {
                                                haptic.medium()
                                                val start =
                                                    inputValue
                                                        .text
                                                val newText =
                                                    "$start #$tag "
                                                onInputValueChange(
                                                    TextFieldValue(
                                                        newText,
                                                        TextRange(
                                                            newText.length,
                                                        ),
                                                    ),
                                                )
                                                showTagSelector =
                                                    false
                                            },
                                            label = { Text("#$tag") },
                                            colors =
                                                FilterChipDefaults
                                                    .filterChipColors(
                                                        containerColor =
                                                            MaterialTheme
                                                                .colorScheme
                                                                .surfaceVariant,
                                                    ),
                                            border = null,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        // Toolbar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Image
                            IconButton(
                                onClick = {
                                    haptic.medium()
                                    onImageClick()
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.Image,
                                    contentDescription =
                                        androidx.compose.ui.res.stringResource(
                                            com.lomo.ui.R.string.cd_add_image,
                                        ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Todo
                            IconButton(
                                onClick = {
                                    haptic.medium()
                                    val start = inputValue.text
                                    val newText =
                                        if (start.isEmpty()) {
                                            "- [ ] "
                                        } else {
                                            "$start\n- [ ] "
                                        }
                                    onInputValueChange(
                                        TextFieldValue(
                                            newText,
                                            TextRange(newText.length),
                                        ),
                                    )
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.CheckBox,
                                    contentDescription =
                                        androidx.compose.ui.res.stringResource(
                                            com.lomo.ui.R.string.cd_add_checkbox,
                                        ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Voice Memo (Mic)
                            val permissionLauncher =
                                androidx.activity.compose.rememberLauncherForActivityResult(
                                    androidx.activity.result.contract.ActivityResultContracts
                                        .RequestPermission(),
                                ) { isGranted ->
                                    if (isGranted) {
                                        onStartRecording()
                                    }
                                }

                            IconButton(
                                onClick = {
                                    haptic.medium()
                                    permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.Mic,
                                    contentDescription =
                                        androidx.compose.ui.res.stringResource(
                                            com.lomo.ui.R.string.cd_add_voice_memo,
                                        ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Tag Toggle
                            IconButton(
                                onClick = {
                                    haptic.medium()
                                    showTagSelector = !showTagSelector
                                },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Label,
                                    contentDescription =
                                        androidx.compose.ui.res.stringResource(
                                            com.lomo.ui.R.string.cd_add_tag,
                                        ),
                                    tint =
                                        if (showTagSelector) {
                                            MaterialTheme.colorScheme
                                                .primary
                                        } else {
                                            MaterialTheme.colorScheme
                                                .onSurfaceVariant
                                        },
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Send - MD3 FilledTonalButton
                            androidx.compose.material3.FilledTonalButton(
                                onClick = {
                                    haptic.heavy()
                                    if (inputValue.text.isNotBlank()) {
                                        onSubmit(inputValue.text.trim())
                                    }
                                },
                                enabled = inputValue.text.isNotBlank(),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Send,
                                    contentDescription =
                                        androidx.compose.ui.res.stringResource(
                                            com.lomo.ui.R.string.cd_send,
                                        ),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
