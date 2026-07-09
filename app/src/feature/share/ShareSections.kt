package com.lomo.app.feature.share

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

private val SETTINGS_TONAL_ELEVATION = 1.dp
private const val SETTINGS_CONTENT_RESIZE_DURATION_MILLIS = 280
private val SETTINGS_LOCK_ICON_PADDING = 8.dp
private const val SETTINGS_PAIRING_PANEL_ALPHA = 0.5f
private const val SETTINGS_PAIRING_ENTER_DURATION_MILLIS = 180
private const val SETTINGS_PAIRING_EXIT_DURATION_MILLIS = 150
private const val SETTINGS_NAME_ACTION_ENTER_DURATION_MILLIS = 220
private const val SETTINGS_NAME_ACTION_EXIT_DURATION_MILLIS = 180
private const val SETTINGS_NAME_ACTION_ENTER_OFFSET_DIVISOR = 2
private const val SETTINGS_NAME_ACTION_EXIT_OFFSET_DIVISOR = 3

private const val MEMO_PREVIEW_CONTAINER_ALPHA = 0.45f
private const val MEMO_PREVIEW_MAX_LINES = 5

@Composable
fun LanShareSettingsCard(
    e2eEnabled: Boolean,
    pairingConfigured: Boolean,
    deviceNameInput: String,
    saveNameEnabled: Boolean,
    onToggleE2e: (Boolean) -> Unit,
    onOpenPairingDialog: () -> Unit,
    onDeviceNameInputChange: (String) -> Unit,
    onNameFieldFocusChanged: (Boolean) -> Unit,
    onSaveDeviceName: () -> Unit,
    onUseSystemDeviceName: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isNameFieldFocused by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = AppShapes.Large,
        tonalElevation = SETTINGS_TONAL_ELEVATION,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(AppSpacing.Medium)
                    .animateContentSize(animationSpec = tween(SETTINGS_CONTENT_RESIZE_DURATION_MILLIS)),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
        ) {
            LanShareSecurityHeader(
                e2eEnabled = e2eEnabled,
                onToggleE2e = onToggleE2e,
            )
            PairingConfigurationVisibility(
                visible = e2eEnabled,
                pairingConfigured = pairingConfigured,
                onOpenPairingDialog = onOpenPairingDialog,
            )
            DeviceNameInputField(
                deviceNameInput = deviceNameInput,
                onDeviceNameInputChange = onDeviceNameInputChange,
                onNameFieldFocusChanged = { isFocused ->
                    isNameFieldFocused = isFocused
                    onNameFieldFocusChanged(isFocused)
                },
                onSaveDeviceName = onSaveDeviceName,
            )
            DeviceNameActionsVisibility(
                visible = isNameFieldFocused,
                saveNameEnabled = saveNameEnabled,
                onSaveDeviceName = onSaveDeviceName,
                onUseSystemDeviceName = onUseSystemDeviceName,
            )
        }
    }
}

@Composable
private fun PairingConfigurationVisibility(
    visible: Boolean,
    pairingConfigured: Boolean,
    onOpenPairingDialog: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(tween(SETTINGS_PAIRING_ENTER_DURATION_MILLIS)) +
                expandVertically(animationSpec = tween(SETTINGS_PAIRING_ENTER_DURATION_MILLIS)),
        exit =
            fadeOut(tween(SETTINGS_PAIRING_EXIT_DURATION_MILLIS)) +
                shrinkVertically(animationSpec = tween(SETTINGS_PAIRING_EXIT_DURATION_MILLIS)),
    ) {
        PairingConfigurationPanel(
            pairingConfigured = pairingConfigured,
            onOpenPairingDialog = onOpenPairingDialog,
        )
    }
}

@Composable
private fun DeviceNameInputField(
    deviceNameInput: String,
    onDeviceNameInputChange: (String) -> Unit,
    onNameFieldFocusChanged: (Boolean) -> Unit,
    onSaveDeviceName: () -> Unit,
) {
    OutlinedTextField(
        value = deviceNameInput,
        onValueChange = onDeviceNameInputChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { onNameFieldFocusChanged(it.isFocused) },
        singleLine = true,
        label = { Text(stringResource(R.string.share_device_name_label)) },
        placeholder = { Text(stringResource(R.string.share_device_name_placeholder)) },
        keyboardOptions =
            androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = KeyboardType.Text,
            ),
        keyboardActions =
            androidx.compose.foundation.text.KeyboardActions(
                onDone = { onSaveDeviceName() },
            ),
    )
}

@Composable
private fun DeviceNameActionsVisibility(
    visible: Boolean,
    saveNameEnabled: Boolean,
    onSaveDeviceName: () -> Unit,
    onUseSystemDeviceName: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(tween(SETTINGS_PAIRING_ENTER_DURATION_MILLIS)) +
                slideInVertically(
                    initialOffsetY = { -it / SETTINGS_NAME_ACTION_ENTER_OFFSET_DIVISOR },
                    animationSpec = tween(SETTINGS_NAME_ACTION_ENTER_DURATION_MILLIS),
                ),
        exit =
            fadeOut(tween(SETTINGS_PAIRING_EXIT_DURATION_MILLIS)) +
                slideOutVertically(
                    targetOffsetY = { -it / SETTINGS_NAME_ACTION_EXIT_OFFSET_DIVISOR },
                    animationSpec = tween(SETTINGS_NAME_ACTION_EXIT_DURATION_MILLIS),
                ),
    ) {
        DeviceNameActionsRow(
            saveNameEnabled = saveNameEnabled,
            onSaveDeviceName = onSaveDeviceName,
            onUseSystemDeviceName = onUseSystemDeviceName,
        )
    }
}

@Composable
private fun StatusBadge(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color =
            if (active) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        shape = AppShapes.Full,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (active) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = AppSpacing.ExtraSmall),
        )
    }
}

@Composable
fun MemoPreviewCard(
    content: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MEMO_PREVIEW_CONTAINER_ALPHA),
        shape = AppShapes.Large,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
        ) {
            Text(
                text = stringResource(R.string.share_memo_preview_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AppSpacing.Small))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = MEMO_PREVIEW_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LanShareSecurityHeader(
    e2eEnabled: Boolean,
    onToggleE2e: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(SETTINGS_LOCK_ICON_PADDING),
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.MediumSmall))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.share_e2e_enabled_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.share_e2e_enabled_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusBadge(
            text =
                if (e2eEnabled) {
                    stringResource(R.string.share_e2e_state_enabled)
                } else {
                    stringResource(R.string.share_e2e_state_disabled)
                },
            active = e2eEnabled,
        )
        Spacer(modifier = Modifier.width(AppSpacing.Small))
        Switch(
            checked = e2eEnabled,
            onCheckedChange = onToggleE2e,
        )
    }
}

@Composable
private fun PairingConfigurationPanel(
    pairingConfigured: Boolean,
    onOpenPairingDialog: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = SETTINGS_PAIRING_PANEL_ALPHA),
        shape = AppShapes.Medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AppSpacing.MediumSmall,
                        vertical = AppSpacing.Small,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.share_e2e_password_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        if (pairingConfigured) {
                            stringResource(R.string.share_e2e_password_configured)
                        } else {
                            stringResource(R.string.share_e2e_password_not_set)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (pairingConfigured) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }
            FilledTonalButton(onClick = onOpenPairingDialog) {
                Text(stringResource(R.string.share_e2e_password_set))
            }
        }
    }
}

@Composable
private fun DeviceNameActionsRow(
    saveNameEnabled: Boolean,
    onSaveDeviceName: () -> Unit,
    onUseSystemDeviceName: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onUseSystemDeviceName) {
            Text(stringResource(R.string.share_device_name_use_system))
        }
        Spacer(modifier = Modifier.width(AppSpacing.Small))
        Button(
            onClick = onSaveDeviceName,
            enabled = saveNameEnabled,
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}
