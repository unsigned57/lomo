package com.lomo.ui.component.input

import android.Manifest
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.lomo.ui.R
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.util.AppHapticFeedback

@Composable
internal fun InputSheetScaffold(
    isSheetVisible: Boolean,
    scrimAlpha: Float,
    onRequestDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val animatedScrimAlpha by animateFloatAsState(
        targetValue = scrimAlpha,
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MotionTokens.DurationLong2,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
        label = "InputSheetScrimAlpha",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = animatedScrimAlpha))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onRequestDismiss() }) },
        )
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
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) { detectTapGestures(onTap = { }) },
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
internal fun InputEditorPanel(
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
        val inputTextStyle =
            remember(inputValue.text, bodyLargeStyle) {
                bodyLargeStyle.scriptAwareFor(inputValue.text)
            }

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
        InputEditorTagSelector(
            availableTags = availableTags,
            showTagSelector = showTagSelector,
            slots = slots,
            onTagSelected = onTagSelected,
        )
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
private fun InputEditorTagSelector(
    availableTags: List<String>,
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
        slots.tagSelectorBar(
            TagSelectorBarState(availableTags = availableTags),
            TagSelectorBarCallbacks(onTagSelected = onTagSelected),
        )
    }
}

@Composable
internal fun InputEditorToolbar(
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
        InputToolbarIconButton(
            icon = Icons.Rounded.PhotoCamera,
            contentDescription = stringResource(R.string.cd_take_photo),
            onClick = onCameraClick,
            haptic = haptic,
        )
        InputToolbarIconButton(
            icon = Icons.Rounded.Image,
            contentDescription = stringResource(R.string.cd_add_image),
            onClick = onImageClick,
            haptic = haptic,
        )
        InputToolbarIconButton(
            icon = Icons.Rounded.Mic,
            contentDescription = stringResource(R.string.cd_add_voice_memo),
            onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            haptic = haptic,
        )
        InputToolbarIconButton(
            icon = Icons.AutoMirrored.Rounded.Label,
            contentDescription = stringResource(R.string.cd_add_tag),
            tint =
                if (showTagSelector) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            onClick = onToggleTagSelector,
            haptic = haptic,
        )
        InputToolbarIconButton(
            icon = Icons.Rounded.CheckBox,
            contentDescription = stringResource(R.string.cd_add_checkbox),
            onClick = onInsertTodo,
            haptic = haptic,
        )
        Spacer(modifier = Modifier.weight(1f))
        InputToolbarSubmitButton(
            isSubmitEnabled = isSubmitEnabled,
            onSubmit = onSubmit,
            haptic = haptic,
        )
    }
}

@Composable
private fun InputToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    haptic: AppHapticFeedback,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
        onClick = {
            haptic.medium()
            onClick()
        },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}

@Composable
private fun InputToolbarSubmitButton(
    isSubmitEnabled: Boolean,
    onSubmit: () -> Unit,
    haptic: AppHapticFeedback,
) {
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

internal fun fadeScaleContentTransition(): ContentTransform =
    (
        fadeIn(
            animationSpec =
                androidx.compose.animation.core
                    .tween(MotionTokens.DurationMedium2),
        ) +
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
            animationSpec =
                androidx.compose.animation.core
                    .tween(durationMillis = MotionTokens.DurationShort4),
        ),
    )
