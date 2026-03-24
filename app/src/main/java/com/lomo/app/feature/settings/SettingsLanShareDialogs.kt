package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.usecase.LanSharePairingCodePolicy

@Composable
internal fun LanShareDialogs(
    uiState: SettingsScreenUiState,
    lanShareFeature: SettingsLanShareFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    LanPairingDialog(
        uiState = uiState,
        lanShareFeature = lanShareFeature,
        dialogState = dialogState,
    )
    DeviceNameDialog(
        lanShareFeature = lanShareFeature,
        dialogState = dialogState,
    )
}

@Composable
private fun LanPairingDialog(
    uiState: SettingsScreenUiState,
    lanShareFeature: SettingsLanShareFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showLanPairingDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = {
            lanShareFeature.clearPairingCodeError()
            dialogState.showLanPairingDialog = false
        },
        title = { Text(stringResource(R.string.settings_lan_share_pairing_dialog_title)) },
        text = {
            LanPairingDialogContent(
                uiState = uiState,
                lanShareFeature = lanShareFeature,
                dialogState = dialogState,
            )
        },
        confirmButton = {
            LanPairingDialogConfirmButton(
                lanShareFeature = lanShareFeature,
                dialogState = dialogState,
            )
        },
        dismissButton = {
            TextButton(
                onClick = {
                    lanShareFeature.clearPairingCodeError()
                    dialogState.showLanPairingDialog = false
                },
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun LanPairingDialogContent(
    uiState: SettingsScreenUiState,
    lanShareFeature: SettingsLanShareFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_lan_share_pairing_dialog_message),
            style = MaterialTheme.typography.bodyMedium,
        )
        LanPairingCodeField(
            uiState = uiState,
            lanShareFeature = lanShareFeature,
            dialogState = dialogState,
        )
        if (uiState.lanShare.pairingConfigured) {
            TextButton(
                onClick = {
                    lanShareFeature.clearLanSharePairingCode()
                    dialogState.showLanPairingDialog = false
                },
            ) {
                Text(stringResource(R.string.action_clear_pairing_code))
            }
        }
    }
}

@Composable
private fun LanPairingCodeField(
    uiState: SettingsScreenUiState,
    lanShareFeature: SettingsLanShareFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    OutlinedTextField(
        value = dialogState.lanPairingCodeInput,
        onValueChange = {
            dialogState.lanPairingCodeInput = it
            if (uiState.lanShare.pairingCodeError != null) {
                lanShareFeature.clearPairingCodeError()
            }
        },
        singleLine = true,
        label = { Text(stringResource(R.string.settings_lan_share_pairing_hint)) },
        visualTransformation =
            if (dialogState.lanPairingCodeVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        trailingIcon = {
            TextButton(onClick = { dialogState.lanPairingCodeVisible = !dialogState.lanPairingCodeVisible }) {
                Text(
                    text =
                        if (dialogState.lanPairingCodeVisible) {
                            stringResource(R.string.share_password_hide)
                        } else {
                            stringResource(R.string.share_password_show)
                        },
                )
            }
        },
        isError = uiState.lanShare.pairingCodeError != null,
        supportingText =
            uiState.lanShare.pairingCodeError?.let {
                {
                    Text(SettingsErrorPresenter.pairingCodeMessage(it))
                }
            },
    )
}

@Composable
private fun LanPairingDialogConfirmButton(
    lanShareFeature: SettingsLanShareFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    TextButton(
        onClick = {
            lanShareFeature.updateLanSharePairingCode(dialogState.lanPairingCodeInput)
            if (LanSharePairingCodePolicy.shouldDismissDialogAfterSave(dialogState.lanPairingCodeInput)) {
                dialogState.showLanPairingDialog = false
            }
        },
    ) {
        Text(stringResource(R.string.action_save))
    }
}

@Composable
private fun DeviceNameDialog(
    lanShareFeature: SettingsLanShareFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showDeviceNameDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showDeviceNameDialog = false },
        title = { Text(stringResource(R.string.share_device_name_label)) },
        text = { DeviceNameDialogContent(dialogState = dialogState, lanShareFeature = lanShareFeature) },
        confirmButton = {
            TextButton(
                onClick = {
                    lanShareFeature.updateLanShareDeviceName(dialogState.deviceNameInput)
                    dialogState.showDeviceNameDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showDeviceNameDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DeviceNameDialogContent(
    dialogState: SettingsDialogState,
    lanShareFeature: SettingsLanShareFeatureViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.share_device_name_placeholder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = dialogState.deviceNameInput,
            onValueChange = { dialogState.deviceNameInput = it },
            singleLine = true,
            label = { Text(stringResource(R.string.share_device_name_label)) },
        )
        TextButton(
            onClick = {
                lanShareFeature.updateLanShareDeviceName("")
                dialogState.showDeviceNameDialog = false
            },
        ) {
            Text(stringResource(R.string.share_device_name_use_system))
        }
    }
}
