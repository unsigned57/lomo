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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.ui.R
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.AppHapticFeedback

@Composable
internal fun InputEditorToolbar(
    toggleIcon: InputEditorToggleIcon,
    showTagSelector: Boolean,
    isExpanded: Boolean,
    isSubmitEnabled: Boolean,
    enabled: Boolean,
    onToggleExpanded: () -> Unit,
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onStartRecording: () -> Unit,
    onToggleTagSelector: () -> Unit,
    onInsertTodo: () -> Unit,
    onInsertUnderline: () -> Unit,
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
                onCameraClick = onCameraClick,
                onImageClick = onImageClick,
                onStartRecording = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onToggleTagSelector = onToggleTagSelector,
                onInsertTodo = onInsertTodo,
                onInsertUnderline = onInsertUnderline,
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
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onStartRecording: () -> Unit,
    onToggleTagSelector: () -> Unit,
    onInsertTodo: () -> Unit,
    onInsertUnderline: () -> Unit,
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
                "underline",
            )
        }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(end = AppSpacing.Small),
    ) {
        items(toolIds, key = { it }) { toolId ->
            when (toolId) {
                "camera" ->
                    InputToolbarIconButton(
                        icon = Icons.Rounded.PhotoCamera,
                        contentDescription = stringResource(R.string.cd_take_photo),
                        enabled = enabled,
                        onClick = onCameraClick,
                        haptic = haptic,
                    )

                "image" ->
                    InputToolbarIconButton(
                        icon = Icons.Rounded.Image,
                        contentDescription = stringResource(R.string.cd_add_image),
                        enabled = enabled,
                        onClick = onImageClick,
                        haptic = haptic,
                    )

                "record" ->
                    InputToolbarIconButton(
                        icon = Icons.Rounded.Mic,
                        contentDescription = stringResource(R.string.cd_add_voice_memo),
                        enabled = enabled,
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
                        enabled = enabled,
                        onClick = onToggleTagSelector,
                        haptic = haptic,
                    )

                "todo" ->
                    InputToolbarIconButton(
                        icon = Icons.Rounded.CheckBox,
                        contentDescription = stringResource(R.string.cd_add_checkbox),
                        enabled = enabled,
                        onClick = onInsertTodo,
                        haptic = haptic,
                    )

                "underline" ->
                    InputToolbarIconButton(
                        icon = Icons.Rounded.FormatUnderlined,
                        contentDescription = stringResource(R.string.cd_add_underline),
                        enabled = enabled,
                        onClick = onInsertUnderline,
                        haptic = haptic,
                    )
            }
        }
    }
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
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
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
