package com.lomo.ui.component.input

import android.Manifest
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import com.lomo.ui.R
import com.lomo.ui.text.applyMemoParagraphAppearance
import com.lomo.ui.text.applyMemoParagraphTextStyle
import com.lomo.ui.text.rawMemoParagraphSpacing
import com.lomo.ui.text.resolveRawMemoPlainTextStyle
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.theme.memoHintTextStyle
import com.lomo.ui.util.AppHapticFeedback
import kotlin.math.roundToInt

private val InputEditorContainerPaddingHorizontal = 16.dp
private val InputEditorContainerPaddingVertical = 12.dp

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
    val typography = MaterialTheme.typography
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Medium),
    ) {
        val inputTextStyle =
            remember(inputValue.text, typography) {
                resolveRawMemoPlainTextStyle(
                    typography = typography,
                    text = inputValue.text,
                )
            }
        InputEditorTextField(
            inputValue = inputValue,
            hintText = hintText,
            focusRequester = focusRequester,
            textStyle = inputTextStyle,
            onTextChange = onTextChange,
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
private fun InputEditorTextField(
    inputValue: TextFieldValue,
    hintText: String,
    focusRequester: FocusRequester,
    textStyle: androidx.compose.ui.text.TextStyle,
    onTextChange: (TextFieldValue) -> Unit,
) {
    val density = LocalDensity.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val displayStyle =
        remember(textStyle, onSurface) {
            textStyle.copy(color = onSurface)
        }
    val cursorColor = MaterialTheme.colorScheme.primary.toArgb()
    val paragraphSpacingPx = with(density) { rawMemoParagraphSpacing().roundToPx() }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(AppShapes.Large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(
                    horizontal = InputEditorContainerPaddingHorizontal,
                    vertical = InputEditorContainerPaddingVertical,
                ),
    ) {
        if (inputValue.text.isEmpty()) {
            InputHintPlaceholder(hintText = hintText)
        }

        AndroidView(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            factory = { context ->
                MemoInputEditText(context).apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    background = null
                    includeFontPadding = false
                    setPadding(0, 0, 0, 0)
                    setTextIsSelectable(true)
                    isSingleLine = false
                    minLines = INPUT_EDITOR_MIN_LINES
                    maxLines = INPUT_EDITOR_MAX_LINES
                    gravity = Gravity.START or Gravity.TOP
                    movementMethod = ArrowKeyMovementMethod.getInstance()
                    imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                    clearCursorDrawableIfSupported()
                    highlightColor = cursorColor
                    addTextChangedListener(
                        object : TextWatcher {
                            override fun beforeTextChanged(
                                s: CharSequence?,
                                start: Int,
                                count: Int,
                                after: Int,
                            ) = Unit

                            override fun onTextChanged(
                                s: CharSequence?,
                                start: Int,
                                before: Int,
                                count: Int,
                            ) = Unit

                            override fun afterTextChanged(s: Editable?) {
                                if (isUpdatingFromModel) return
                                onTextChange(currentTextFieldValue())
                            }
                        },
                    )
                    onSelectionChangedListener = {
                        if (!isUpdatingFromModel) {
                            onTextChange(currentTextFieldValue())
                        }
                    }
                }
            },
            update = { editText ->
                editText.setCursorColor(cursorColor)
                editText.isUpdatingFromModel = true
                val shouldReplacePresentation =
                    shouldReplaceMemoInputPresentationText(
                        currentText = editText.text ?: "",
                        desiredText = inputValue.text,
                        lastAppliedParagraphSpacingPx = editText.lastAppliedParagraphSpacingPx,
                        desiredParagraphSpacingPx = paragraphSpacingPx,
                    )
                if (shouldReplacePresentation) {
                    editText.applyMemoParagraphTextStyle(
                        text = buildRawMemoEditorPresentationText(inputValue.text, paragraphSpacingPx),
                        style = displayStyle,
                        density = density,
                        maxLines = INPUT_EDITOR_MAX_LINES,
                        overflow = TextOverflow.Clip,
                        selectable = true,
                    )
                    editText.lastAppliedParagraphSpacingPx = paragraphSpacingPx
                } else {
                    editText.applyMemoParagraphAppearance(
                        text = editText.text ?: "",
                        style = displayStyle,
                        density = density,
                        maxLines = INPUT_EDITOR_MAX_LINES,
                        overflow = TextOverflow.Clip,
                        selectable = true,
                    )
                }
                editText.syncWith(inputValue)
                editText.isUpdatingFromModel = false
            },
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
            style = MaterialTheme.typography.memoHintTextStyle().scriptAwareFor(targetHint),
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
