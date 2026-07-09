package com.lomo.ui.component.input

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.*
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun InputEditorToolbar(
    toggleIcon: InputEditorToggleIcon,
    isExpanded: Boolean,
    isSubmitEnabled: Boolean,
    enabled: Boolean,
    onToggleExpanded: () -> Unit,
    tools: ImmutableList<InputToolbarTool>,
    onEditorCommand: (InputEditorCommand) -> Unit,
    onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
    onSubmit: () -> Unit,
    benchmarkSubmitTag: String?,
    haptic: AppHapticFeedback,
    modifier: Modifier = Modifier,
) {
    var pendingPermissionCommand by remember { mutableStateOf<InputEditorCommand?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            val command = pendingPermissionCommand
            pendingPermissionCommand = null
            if (isGranted) {
                command?.let(onEditorCommand)
            }
        }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            InputToolbarScrollableTools(
                enabled = enabled,
                tools = tools,
                onToolClick = { tool ->
                    val requiredPermission = tool.requiredPermission
                    if (requiredPermission != null) {
                        pendingPermissionCommand = tool.command
                        permissionLauncher.launch(requiredPermission)
                    } else {
                        onEditorCommand(tool.command)
                    }
                },
                onToolbarOrderChanged = onToolbarOrderChanged,
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
    enabled: Boolean,
    tools: ImmutableList<InputToolbarTool>,
    onToolClick: (InputToolbarTool) -> Unit,
    onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
    haptic: AppHapticFeedback,
) {
    val toolById = remember(tools) { tools.associateBy(InputToolbarTool::id) }
    val toolIds = remember(tools) { tools.map(InputToolbarTool::id).toMutableStateList() }
    val lazyRowState = rememberLazyListState()
    val reorderableLazyRowState =
        rememberReorderableLazyListState(lazyRowState) { from, to ->
            val fromKey = (from.key as? String)?.let(InputToolbarActionId::fromPersistedId)
                ?: return@rememberReorderableLazyListState
            val toKey = (to.key as? String)?.let(InputToolbarActionId::fromPersistedId)
                ?: return@rememberReorderableLazyListState
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
        items(toolIds, key = { it.persistedId }) { toolId ->
            ReorderableItem(
                state = reorderableLazyRowState,
                key = toolId.persistedId,
            ) { isDragging ->
                val dragModifier =
                    Modifier.longPressDraggableHandle(
                        onDragStarted = { haptic.heavy() },
                        onDragStopped = { onToolbarOrderChanged(toolIds.toList()) },
                    ).graphicsLayer {
                        if (isDragging) {
                            scaleX = InputSheetTokens.ToolbarDragScaleFactor
                            scaleY = InputSheetTokens.ToolbarDragScaleFactor
                            alpha = InputSheetTokens.ToolbarDragAlpha
                        }
                    }
                val tool = toolById.getValue(toolId)
                InputToolbarToolButton(
                    tool = tool,
                    enabled = enabled && tool.enabled,
                    onToolClick = onToolClick,
                    haptic = haptic,
                    modifier = dragModifier,
                )
            }
        }
    }
}

@Composable
private fun InputToolbarToolButton(
    tool: InputToolbarTool,
    enabled: Boolean,
    onToolClick: (InputToolbarTool) -> Unit,
    haptic: AppHapticFeedback,
    modifier: Modifier = Modifier,
) {
    InputToolbarToolIconButton(
        icon = tool.icon,
        contentDescriptionRes = tool.contentDescriptionRes,
        enabled = enabled,
        onClick = { onToolClick(tool) },
        haptic = haptic,
        modifier = modifier,
        tint =
            when (tool.tintRole) {
                InputToolbarToolTintRole.Default -> MaterialTheme.colorScheme.onSurfaceVariant
                InputToolbarToolTintRole.Highlight -> MaterialTheme.colorScheme.primary
            },
    )
}

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
                    org.jetbrains.compose.resources.stringResource(
                        when (toggleIcon) {
                            InputEditorToggleIcon.Expand -> Res.string.cd_expand
                            InputEditorToggleIcon.Collapse -> Res.string.cd_collapse
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
            contentDescription = org.jetbrains.compose.resources.stringResource(Res.string.cd_send),
            modifier = Modifier.size(InputSheetTokens.ToolbarSubmitIconSize),
        )
    }
}
