package com.lomo.ui.component.input

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.component.common.lomoCompactInteractiveHeight
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.cd_collapse
import com.lomo.ui.generated.resources.cd_expand
import com.lomo.ui.generated.resources.cd_send
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun InputEditorToolbar(
    state: InputEditorToolbarState,
    callbacks: InputEditorToolbarCallbacks,
    modifier: Modifier = Modifier,
) {
    var pendingPermissionCommand by remember { mutableStateOf<InputEditorCommand?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            val command = pendingPermissionCommand
            pendingPermissionCommand = null
            if (isGranted) {
                command?.let(callbacks.onEditorCommand)
            }
        }
    val dispatchToolClick: (InputToolbarTool) -> Unit = { tool ->
        val requiredPermission = tool.requiredPermission
        if (requiredPermission != null) {
            pendingPermissionCommand = tool.command
            permissionLauncher.launch(requiredPermission)
        } else {
            callbacks.onEditorCommand(tool.command)
        }
    }

    val primaryTools =
        remember(state.tools) {
            state.tools
                .filter { it.placement == InputToolbarToolPlacement.Primary }
                .toImmutableList()
        }
    val secondaryTools =
        remember(state.tools) {
            state.tools
                .filter { it.placement == InputToolbarToolPlacement.SecondaryTray }
                .toImmutableList()
        }
    var showSecondaryTray by remember { mutableStateOf(false) }
    val motionScheme = LocalLomoMotion.current.layout

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        AnimatedVisibility(
            visible = showSecondaryTray && secondaryTools.isNotEmpty() && state.enabled,
            enter =
            expandVertically(animationSpec = motionScheme.defaultSpatialSpec()) +
                fadeIn(animationSpec = motionScheme.defaultEffectsSpec()),
            exit =
            shrinkVertically(animationSpec = motionScheme.defaultSpatialSpec()) +
                fadeOut(animationSpec = motionScheme.fastEffectsSpec()),
        ) {
            InputToolbarSecondaryTray(
                enabled = state.enabled,
                tools = secondaryTools,
                onToolClick = dispatchToolClick,
                haptic = state.haptic,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier =
                Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = InputSheetTokens.ToolbarIslandShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                InputToolbarPrimaryToolsRow(
                    enabled = state.enabled,
                    tools = primaryTools,
                    hasSecondaryTools = secondaryTools.isNotEmpty(),
                    isSecondaryTrayExpanded = showSecondaryTray,
                    onToggleSecondaryTray = { showSecondaryTray = !showSecondaryTray },
                    onToolClick = dispatchToolClick,
                    haptic = state.haptic,
                )
            }
            InputToolbarSubmitFab(
                isSubmitEnabled = state.isSubmitEnabled && state.enabled,
                onSubmit = callbacks.onSubmit,
                benchmarkTag = state.benchmarkSubmitTag,
                haptic = state.haptic,
            )
        }
    }
}

@Composable
private fun InputToolbarPrimaryToolsRow(
    enabled: Boolean,
    tools: ImmutableList<InputToolbarTool>,
    hasSecondaryTools: Boolean,
    isSecondaryTrayExpanded: Boolean,
    onToggleSecondaryTray: () -> Unit,
    onToolClick: (InputToolbarTool) -> Unit,
    haptic: AppHapticFeedback,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(InputSheetTokens.ToolbarIslandContentPadding),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tools.forEach { tool ->
            InputToolbarToolButton(
                tool = tool,
                enabled = enabled && tool.enabled,
                onToolClick = onToolClick,
                haptic = haptic,
                modifier = Modifier.weight(1f),
            )
        }
        if (hasSecondaryTools) {
            LomoIconButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    haptic.medium()
                    onToggleSecondaryTray()
                },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription =
                    org.jetbrains.compose.resources.stringResource(
                        if (isSecondaryTrayExpanded) {
                            Res.string.cd_collapse
                        } else {
                            Res.string.cd_expand
                        },
                    ),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InputToolbarSecondaryTray(
    enabled: Boolean,
    tools: ImmutableList<InputToolbarTool>,
    onToolClick: (InputToolbarTool) -> Unit,
    haptic: AppHapticFeedback,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = AppSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tools.forEach { tool ->
            val isToolEnabled = enabled && tool.enabled
            val isHighlighted = tool.tintRole == InputToolbarToolTintRole.Highlight
            val containerColor =
                when {
                    !isToolEnabled -> MaterialTheme.colorScheme.surfaceContainerLow
                    isHighlighted -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
            val contentColor =
                when {
                    !isToolEnabled -> InputSheetTokens.disabledToolLabelColor(MaterialTheme.colorScheme)
                    isHighlighted -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            Surface(
                onClick = {
                    haptic.medium()
                    onToolClick(tool)
                },
                enabled = isToolEnabled,
                shape = InputSheetTokens.SecondaryTrayPillShape,
                color = containerColor,
                contentColor = contentColor,
            ) {
                Row(
                    modifier = Modifier.padding(InputSheetTokens.SecondaryTrayPillContentPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(InputSheetTokens.SecondaryTrayIconSize),
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.ExtraSmall))
                    Text(
                        text = stringResource(tool.contentDescriptionRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                    )
                }
            }
        }
    }
}

@Composable
internal fun InputToolbarToolButton(
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
        when {
            !enabled -> InputSheetTokens.disabledToolIconColor(MaterialTheme.colorScheme)
            tool.tintRole == InputToolbarToolTintRole.Highlight -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
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
internal fun InputHeaderToolButton(
    tool: InputToolbarTool,
    enabled: Boolean,
    onToolClick: (InputToolbarTool) -> Unit,
    haptic: AppHapticFeedback,
    modifier: Modifier = Modifier,
) {
    InputHeaderIconButton(
        modifier = modifier,
        icon = tool.icon,
        contentDescription = stringResource(tool.contentDescriptionRes),
        enabled = enabled,
        onClick = { onToolClick(tool) },
        haptic = haptic,
        tint =
        when {
            !enabled -> InputSheetTokens.disabledToolIconColor(MaterialTheme.colorScheme)
            tool.tintRole == InputToolbarToolTintRole.Highlight -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
internal fun InputHeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    haptic: AppHapticFeedback,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    LomoIconButton(
        modifier =
        modifier
            .lomoCompactInteractiveHeight(InputSheetTokens.HeaderIconButtonHeight)
            .size(
                width = InputSheetTokens.HeaderIconButtonWidth,
                height = InputSheetTokens.HeaderIconButtonHeight,
            ),
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
            modifier = Modifier.size(InputSheetTokens.HeaderIconSize),
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
    LomoIconButton(
        modifier = modifier.lomoCompactInteractiveHeight(40.dp),
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
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Send lives at the trailing edge of the adaptive bottom bar. A disabled submit reads as muted
 * colors and the click is rejected before the submit pipeline runs — the submission contract still
 * guards empty/locked sends downstream.
 */
@Composable
private fun InputToolbarSubmitFab(
    isSubmitEnabled: Boolean,
    onSubmit: () -> Unit,
    benchmarkTag: String?,
    haptic: AppHapticFeedback,
) {
    val componentMotion = LocalLomoMotion.current.component
    val containerColor by animateColorAsState(
        targetValue =
        if (isSubmitEnabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "SubmitFabContainerColor",
    )
    val contentColor by animateColorAsState(
        targetValue =
        if (isSubmitEnabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "SubmitFabContentColor",
    )
    val scale by animateFloatAsState(
        targetValue = if (isSubmitEnabled) 1f else 0.94f,
        animationSpec = componentMotion.fastSpatialSpec(),
        label = "SubmitFabScale",
    )
    Surface(
        enabled = isSubmitEnabled,
        onClick = {
            if (isSubmitEnabled) {
                haptic.heavy()
                onSubmit()
            }
        },
        shape = InputSheetTokens.ToolbarIslandShape,
        color = containerColor,
        contentColor = contentColor,
        modifier =
        Modifier
            .size(width = 56.dp, height = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .benchmarkAnchor(benchmarkTag),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = org.jetbrains.compose.resources.stringResource(Res.string.cd_send),
                modifier = Modifier.size(InputSheetTokens.ToolbarSubmitIconSize),
            )
        }
    }
}
