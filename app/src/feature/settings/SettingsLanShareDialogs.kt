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
import androidx.compose.ui.unit.dp
import com.lomo.app.R

@Composable
internal fun LanShareDialogs(
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showDeviceNameDialog) return
    AlertDialog(
        onDismissRequest = { dialogState.showDeviceNameDialog = false },
        title = { Text(stringResource(R.string.share_device_name_label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.share_device_name_placeholder),
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
                        features.lanShare.updateLanShareDeviceName("")
                        dialogState.showDeviceNameDialog = false
                    },
                ) {
                    Text(stringResource(R.string.share_device_name_use_system))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    features.lanShare.updateLanShareDeviceName(dialogState.deviceNameInput)
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
