package com.lomo.ui.component.input

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.ui.R
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val TOOLBAR_DRAG_SCALE_FACTOR = 1.05f
private const val TOOLBAR_DRAG_ALPHA = 0.92f

internal fun inputToolbarToolIds(): List<String> =
    listOf(
        "camera",
        "image",
        "record",
        "tag",
        "location",
        "backfill",
        "todo",
        "reminder",
        "underline",
        "undo",
        "redo",
    )

internal fun resolveInputToolbarToolIds(persistedOrder: List<String>): List<String> {
    val defaults = inputToolbarToolIds()
    val seen = mutableSetOf<String>()
    return buildList {
        persistedOrder
            .asSequence()
            .map(String::trim)
            .filter { toolId -> toolId in defaults }
            .filter(seen::add)
            .forEach(::add)
        defaults
            .asSequence()
            .filter(seen::add)
            .forEach(::add)
    }
}

@Composable
internal fun InputEditorToolbar(
    toggleIcon: InputEditorToggleIcon,
    showTagSelector: Boolean,
    isExpanded: Boolean,
    isSubmitEnabled: Boolean,
    enabled: Boolean,
    onToggleExpanded: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onStartRecording: () -> Unit,
    onLocationClick: () -> Unit,
    hasAttachedLocation: Boolean,
    onBackfillClick: () -> Unit,
    isBackfillEnabled: Boolean,
    onToggleTagSelector: () -> Unit,
    onInsertTodo: () -> Unit,
    onInsertUnderline: () -> Unit,
    onInsertReminder: () -> Unit,
    inputToolbarToolOrder: ImmutableList<String>,
    onInputToolbarToolOrderChanged: (List<String>) -> Unit,
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
                enabled = enabled,
                onUndo = onUndo,
                onRedo = onRedo,
                canUndo = canUndo,
                canRedo = canRedo,
                onCameraClick = onCameraClick,
                onImageClick = onImageClick,
                onStartRecording = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onLocationClick = onLocationClick,
                hasAttachedLocation = hasAttachedLocation,
                onBackfillClick = onBackfillClick,
                isBackfillEnabled =
                    resolveInputToolbarBackfillEnabled(
                        toolbarEnabled = enabled,
                        isBackfillEnabled = isBackfillEnabled,
                    ),
                onToggleTagSelector = onToggleTagSelector,
                onInsertTodo = onInsertTodo,
                onInsertUnderline = onInsertUnderline,
                onInsertReminder = onInsertReminder,
                inputToolbarToolOrder = inputToolbarToolOrder,
                onInputToolbarToolOrderChanged = onInputToolbarToolOrderChanged,
                haptic = haptic,
            )
        }
        InputToolbarTrailingActions(
            toggleIcon = toggleIcon,
            isExpanded = isExpanded,
            isSubmitEnabled = isSubmitEnabled,
            enabled = enabled,
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
    enabled: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onStartRecording: () -> Unit,
    onLocationClick: () -> Unit,
    hasAttachedLocation: Boolean,
    onBackfillClick: () -> Unit,
    isBackfillEnabled: Boolean,
    onToggleTagSelector: () -> Unit,
    onInsertTodo: () -> Unit,
    onInsertUnderline: () -> Unit,
    onInsertReminder: () -> Unit,
    inputToolbarToolOrder: ImmutableList<String>,
    onInputToolbarToolOrderChanged: (List<String>) -> Unit,
    haptic: AppHapticFeedback,
) {
    val resolvedToolIds = remember(inputToolbarToolOrder) { resolveInputToolbarToolIds(inputToolbarToolOrder) }
    val toolIds = remember(resolvedToolIds) { resolvedToolIds.toMutableStateList() }
    val lazyRowState = rememberLazyListState()
    val reorderableLazyRowState =
        rememberReorderableLazyListState(lazyRowState) { from, to ->
            val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
            val toKey = to.key as? String ?: return@rememberReorderableLazyListState
            val fromIndex = toolIds.indexOf(fromKey)
            val toIndex = toolIds.indexOf(toKey)
            if (fromIndex >= 0 && toIndex >= 0) {
                toolIds.add(toIndex, toolIds.removeAt(fromIndex))
            }
        }
    LazyRow(
        state = lazyRowState,
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(end = AppSpacing.Small),
    ) {
        items(toolIds, key = { it }) { toolId ->
            ReorderableItem(
                state = reorderableLazyRowState,
                key = toolId,
            ) { isDragging ->
                val dragModifier =
                    Modifier.longPressDraggableHandle(
                        onDragStarted = { haptic.heavy() },
                        onDragStopped = { onInputToolbarToolOrderChanged(toolIds.toList()) },
                    ).graphicsLayer {
                        if (isDragging) {
                            scaleX = TOOLBAR_DRAG_SCALE_FACTOR
                            scaleY = TOOLBAR_DRAG_SCALE_FACTOR
                            alpha = TOOLBAR_DRAG_ALPHA
                        }
                    }
                InputToolbarToolButton(
                    toolId = toolId,
                    enabled = enabled,
                    showTagSelector = showTagSelector,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    hasAttachedLocation = hasAttachedLocation,
                    isBackfillEnabled = isBackfillEnabled,
                    onUndo = onUndo,
                    onRedo = onRedo,
                    onCameraClick = onCameraClick,
                    onImageClick = onImageClick,
                    onStartRecording = onStartRecording,
                    onLocationClick = onLocationClick,
                    onBackfillClick = onBackfillClick,
                    onToggleTagSelector = onToggleTagSelector,
                    onInsertTodo = onInsertTodo,
                    onInsertUnderline = onInsertUnderline,
                    onInsertReminder = onInsertReminder,
                    haptic = haptic,
                    modifier = dragModifier,
                )
            }
        }
    }
}

@Composable
private fun InputToolbarToolButton(
    toolId: String,
    enabled: Boolean,
    showTagSelector: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    hasAttachedLocation: Boolean,
    isBackfillEnabled: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onStartRecording: () -> Unit,
    onLocationClick: () -> Unit,
    onBackfillClick: () -> Unit,
    onToggleTagSelector: () -> Unit,
    onInsertTodo: () -> Unit,
    onInsertUnderline: () -> Unit,
    onInsertReminder: () -> Unit,
    haptic: AppHapticFeedback,
    modifier: Modifier = Modifier,
) {
    when (toolId) {
        "undo" ->
            InputToolbarToolIconButton(
                Icons.AutoMirrored.Rounded.Undo, R.string.cd_undo, enabled && canUndo, onUndo, haptic,
                modifier = modifier,
            )

        "redo" ->
            InputToolbarToolIconButton(
                Icons.AutoMirrored.Rounded.Redo, R.string.cd_redo, enabled && canRedo, onRedo, haptic,
                modifier = modifier,
            )

        "camera" ->
            InputToolbarToolIconButton(
                Icons.Rounded.PhotoCamera, R.string.cd_take_photo, enabled, onCameraClick, haptic,
                modifier = modifier,
            )

        "image" ->
            InputToolbarToolIconButton(
                Icons.Rounded.Image, R.string.cd_add_image, enabled, onImageClick, haptic,
                modifier = modifier,
            )

        "record" ->
            InputToolbarToolIconButton(
                Icons.Rounded.Mic, R.string.cd_add_voice_memo, enabled, onStartRecording, haptic,
                modifier = modifier,
            )

        "location" ->
            InputToolbarToolIconButton(
                icon = Icons.Rounded.LocationOn,
                contentDescriptionRes = R.string.cd_attach_location,
                enabled = enabled,
                onClick = onLocationClick,
                haptic = haptic,
                modifier = modifier,
                tint =
                    if (hasAttachedLocation) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )

        "backfill" ->
            InputToolbarToolIconButton(
                Icons.Rounded.History, R.string.cd_backfill_memo, isBackfillEnabled, onBackfillClick, haptic,
                modifier = modifier,
            )

        "tag" ->
            InputToolbarToolIconButton(
                icon = Icons.AutoMirrored.Rounded.Label,
                contentDescriptionRes = R.string.cd_add_tag,
                enabled = enabled,
                onClick = onToggleTagSelector,
                haptic = haptic,
                modifier = modifier,
                tint =
                    if (showTagSelector) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )

        "todo" ->
            InputToolbarToolIconButton(
                Icons.Rounded.CheckBox, R.string.cd_add_checkbox, enabled, onInsertTodo, haptic,
                modifier = modifier,
            )

        "reminder" ->
            InputToolbarToolIconButton(
                Icons.Rounded.Alarm, R.string.cd_add_reminder, enabled, onInsertReminder, haptic,
                modifier = modifier,
            )

        "underline" ->
            InputToolbarToolIconButton(
                Icons.Rounded.FormatUnderlined, R.string.cd_add_underline, enabled, onInsertUnderline, haptic,
                modifier = modifier,
            )
    }
}

internal fun resolveInputToolbarBackfillEnabled(
    toolbarEnabled: Boolean,
    isBackfillEnabled: Boolean,
): Boolean = toolbarEnabled && isBackfillEnabled

@Composable
private fun InputToolbarToolIconButton(
    icon: ImageVector,
    contentDescriptionRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    haptic: AppHapticFeedback,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    InputToolbarIconButton(
        modifier = modifier,
        icon = icon,
        contentDescription = stringResource(contentDescriptionRes),
        enabled = enabled,
        onClick = onClick,
        haptic = haptic,
        tint = tint,
    )
}

@Composable
internal fun InputToolbarTrailingActions(
    toggleIcon: InputEditorToggleIcon,
    isExpanded: Boolean,
    isSubmitEnabled: Boolean,
    enabled: Boolean,
    onToggleExpanded: () -> Unit,
    onSubmit: () -> Unit,
    benchmarkSubmitTag: String?,
    haptic: AppHapticFeedback,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
    ) {
        if (!isExpanded) {
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
                enabled = enabled,
                onClick = onToggleExpanded,
                haptic = haptic,
                tint =
                    when (toggleIcon) {
                        InputEditorToggleIcon.Expand -> MaterialTheme.colorScheme.onSurfaceVariant
                        InputEditorToggleIcon.Collapse -> MaterialTheme.colorScheme.primary
                    },
            )
        }
        InputToolbarSubmitButton(
            isSubmitEnabled = isSubmitEnabled && enabled,
            onSubmit = onSubmit,
            benchmarkTag = benchmarkSubmitTag,
            haptic = haptic,
        )
    }
}

@Composable
internal fun InputToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    haptic: AppHapticFeedback,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
        modifier = modifier,
        onClick = {
            haptic.medium()
            onClick()
        },
        enabled = enabled,
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
