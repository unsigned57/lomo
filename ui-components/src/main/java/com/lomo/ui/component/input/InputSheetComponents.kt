package com.lomo.ui.component.input

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import com.lomo.ui.R
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.text.rawMemoParagraphSpacing
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.theme.memoPlatformTextHandleColor
import com.lomo.ui.theme.memoPlatformTextSelectionHighlightColor
import com.lomo.ui.theme.memoEditorTextStyle
import com.lomo.ui.theme.memoHintTextStyle
import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay

private val InputSheetCompactFallbackHeight = 228.dp

@Composable
internal fun InputSheetScaffold(
    isSheetVisible: Boolean,
    isExpanded: Boolean,
    scrimAlpha: Float,
    onRequestDismiss: () -> Unit,
    benchmarkRootTag: String?,
    focusParkingRequester: FocusRequester,
    content: @Composable (InputSheetMotionStage, Modifier) -> Unit,
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
        InputSheetFocusParkingTarget(focusParkingRequester = focusParkingRequester)
        InputSheetDismissScrim(
            scrimAlpha = animatedScrimAlpha,
            onRequestDismiss = onRequestDismiss,
        )
        AnimatedVisibility(
            visible = isSheetVisible,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxSize(),
            enter = inputSheetVisibilityEnterTransition(),
            exit = inputSheetVisibilityExitTransition(),
        ) {
            InputSheetAnimatedSurface(
                isExpanded = isExpanded,
                benchmarkRootTag = benchmarkRootTag,
            ) { motionStage, contentModifier ->
                InputSheetSurfaceContent(
                    motionStage = motionStage,
                    modifier = contentModifier,
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun InputSheetAnimatedSurface(
    isExpanded: Boolean,
    benchmarkRootTag: String?,
    content: @Composable (InputSheetMotionStage, Modifier) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val density = LocalDensity.current
        val fullSurfaceHeightPx = with(density) { maxHeight.roundToPx() }
        val surfaceState =
            rememberInputSheetAnimatedSurfaceState(
                isExpanded = isExpanded,
                fullSurfaceHeightPx = fullSurfaceHeightPx,
                fallbackCompactSurfaceHeightPx =
                    remember(density) {
                        with(density) { InputSheetCompactFallbackHeight.roundToPx() }
                    },
            )
        val animatedInsets = rememberInputSheetAnimatedInsets(motionStage = surfaceState.motionStage)

        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier =
                    Modifier
                        .benchmarkAnchorRoot(benchmarkRootTag)
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .inputSheetSurfaceHeight(
                            motionStage = surfaceState.motionStage,
                            animatedSurfaceHeightPx = surfaceState.animatedSurfaceHeightPx,
                            density = density,
                            onCompactSurfaceHeightChanged = surfaceState.onCompactSurfaceHeightChanged,
                        )
                        .clip(
                            RoundedCornerShape(
                                topStart = surfaceState.animatedCornerRadius,
                                topEnd = surfaceState.animatedCornerRadius,
                            ),
                        )
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) { detectTapGestures(onTap = { }) },
            ) {
                content(
                    surfaceState.motionStage,
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (surfaceState.motionStage.usesExpandedSurfaceForm()) {
                                Modifier.fillMaxHeight()
                            } else {
                                Modifier
                            },
                        )
                        .padding(
                            top = animatedInsets.top,
                            bottom = animatedInsets.bottom,
                        )
                        .windowInsetsPadding(WindowInsets.ime),
                )
            }
        }
    }
}

@Composable
internal fun InputSheetDragHandle(modifier: Modifier = Modifier) {
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
    motionStage: InputSheetMotionStage,
    inputValue: TextFieldValue,
    hintText: String,
    availableTags: ImmutableList<String>,
    showTagSelector: Boolean,
    focusRequester: FocusRequester,
    onEditorReady: (MemoInputEditText) -> Unit,
    onTextChange: (TextFieldValue) -> Unit,
    onTagSelected: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    onToggleTagSelector: () -> Unit,
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onStartRecording: () -> Unit,
    onInsertTodo: () -> Unit,
    onSubmit: () -> Unit,
    benchmarkEditorTag: String?,
    benchmarkSubmitTag: String?,
    slots: InputSheetSlots,
    haptic: AppHapticFeedback,
) {
    val isExpanded = motionStage != InputSheetMotionStage.Compact
    val typography = MaterialTheme.typography
    val inputTextStyle = remember(typography) { typography.memoEditorTextStyle() }
    val hintTextStyle = remember(typography) { typography.memoHintTextStyle() }
    val chromeState =
        remember(isExpanded, inputValue.text, hintText) {
            resolveInputEditorChromeState(
                isExpanded = isExpanded,
                inputText = inputValue.text,
                hintText = hintText,
            )
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                .padding(AppSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
    ) {
        InputEditorTextField(
            isExpanded = isExpanded,
            showsPlaceholder = chromeState.showsPlaceholder,
            inputValue = inputValue,
            hintText = hintText,
            focusRequester = focusRequester,
            onEditorReady = onEditorReady,
            textStyle = inputTextStyle,
            placeholderTextStyle = hintTextStyle,
            benchmarkEditorTag = benchmarkEditorTag,
            modifier = if (isExpanded) Modifier.weight(1f) else Modifier,
            onTextChange = onTextChange,
        )
        InputEditorTagSelector(
            availableTags = availableTags,
            showTagSelector = showTagSelector,
            slots = slots,
            onTagSelected = onTagSelected,
        )
        InputEditorToolbar(
            toggleIcon = chromeState.toggleIcon,
            showTagSelector = showTagSelector,
            isSubmitEnabled = inputValue.text.isNotBlank(),
            onToggleExpanded = onToggleExpanded,
            onCameraClick = onCameraClick,
            onImageClick = onImageClick,
            onStartRecording = onStartRecording,
            onToggleTagSelector = onToggleTagSelector,
            onInsertTodo = onInsertTodo,
            onSubmit = onSubmit,
            benchmarkSubmitTag = benchmarkSubmitTag,
            haptic = haptic,
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
    toggleIcon: InputEditorToggleIcon,
    showTagSelector: Boolean,
    isSubmitEnabled: Boolean,
    onToggleExpanded: () -> Unit,
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onStartRecording: () -> Unit,
    onToggleTagSelector: () -> Unit,
    onInsertTodo: () -> Unit,
    onSubmit: () -> Unit,
    benchmarkSubmitTag: String?,
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
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            InputToolbarScrollableTools(
                showTagSelector = showTagSelector,
                onCameraClick = onCameraClick,
                onImageClick = onImageClick,
                onStartRecording = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onToggleTagSelector = onToggleTagSelector,
                onInsertTodo = onInsertTodo,
                haptic = haptic,
            )
        }
        InputToolbarTrailingActions(
            toggleIcon = toggleIcon,
            isSubmitEnabled = isSubmitEnabled,
            onToggleExpanded = onToggleExpanded,
            onSubmit = onSubmit,
            benchmarkSubmitTag = benchmarkSubmitTag,
            haptic = haptic,
        )
    }
}

@Composable
private fun InputToolbarScrollableTools(
    showTagSelector: Boolean,
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onStartRecording: () -> Unit,
    onToggleTagSelector: () -> Unit,
    onInsertTodo: () -> Unit,
    haptic: AppHapticFeedback,
) {
    val toolIds =
        remember(showTagSelector) {
            listOf(
                "camera",
                "image",
                "record",
                "tag",
                "todo",
            )
        }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
        contentPadding = PaddingValues(end = AppSpacing.Small),
    ) {
        items(toolIds, key = { it }) { toolId ->
            when (toolId) {
                "camera" ->
                    InputToolbarIconButton(
                        icon = Icons.Rounded.PhotoCamera,
                        contentDescription = stringResource(R.string.cd_take_photo),
                        onClick = onCameraClick,
                        haptic = haptic,
                    )

                "image" ->
                    InputToolbarIconButton(
                        icon = Icons.Rounded.Image,
                        contentDescription = stringResource(R.string.cd_add_image),
                        onClick = onImageClick,
                        haptic = haptic,
                    )

                "record" ->
                    InputToolbarIconButton(
                        icon = Icons.Rounded.Mic,
                        contentDescription = stringResource(R.string.cd_add_voice_memo),
                        onClick = onStartRecording,
                        haptic = haptic,
                    )

                "tag" ->
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

                "todo" ->
                    InputToolbarIconButton(
                        icon = Icons.Rounded.CheckBox,
                        contentDescription = stringResource(R.string.cd_add_checkbox),
                        onClick = onInsertTodo,
                        haptic = haptic,
                    )
            }
        }
    }
}

@Composable
private fun InputToolbarTrailingActions(
    toggleIcon: InputEditorToggleIcon,
    isSubmitEnabled: Boolean,
    onToggleExpanded: () -> Unit,
    onSubmit: () -> Unit,
    benchmarkSubmitTag: String?,
    haptic: AppHapticFeedback,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
    ) {
        InputToolbarIconButton(
            icon =
                when (toggleIcon) {
                    InputEditorToggleIcon.Expand -> Icons.Rounded.KeyboardArrowUp
                    InputEditorToggleIcon.Collapse -> Icons.Rounded.KeyboardArrowDown
                },
            contentDescription =
                stringResource(
                    when (toggleIcon) {
                        InputEditorToggleIcon.Expand -> R.string.cd_expand
                        InputEditorToggleIcon.Collapse -> R.string.cd_collapse
                    },
                ),
            onClick = onToggleExpanded,
            haptic = haptic,
            tint =
                when (toggleIcon) {
                    InputEditorToggleIcon.Expand -> MaterialTheme.colorScheme.onSurfaceVariant
                    InputEditorToggleIcon.Collapse -> MaterialTheme.colorScheme.primary
                },
        )
        InputToolbarSubmitButton(
            isSubmitEnabled = isSubmitEnabled,
            onSubmit = onSubmit,
            benchmarkTag = benchmarkSubmitTag,
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
    benchmarkTag: String?,
    haptic: AppHapticFeedback,
) {
    FilledTonalButton(
        onClick = {
            haptic.heavy()
            onSubmit()
        },
        enabled = isSubmitEnabled,
        modifier = Modifier.benchmarkAnchor(benchmarkTag),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.Send,
            contentDescription = stringResource(R.string.cd_send),
            modifier = Modifier.size(18.dp),
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
