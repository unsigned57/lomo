package com.lomo.app.feature.share

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.lomo.app.R
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

private const val DEVICE_NAME_ACTIONS_ENTER_MILLIS = 220
private const val DEVICE_NAME_ACTIONS_EXIT_MILLIS = 180

@Composable
fun LanShareSettingsCard(
    deviceNameInput: String,
    saveNameEnabled: Boolean,
    onDeviceNameInputChange: (String) -> Unit,
    onNameFieldFocusChanged: (Boolean) -> Unit,
    onSaveDeviceName: () -> Unit,
    onUseSystemDeviceName: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = AppShapes.Large,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            OutlinedTextField(
                value = deviceNameInput,
                onValueChange = onDeviceNameInputChange,
                modifier = Modifier.fillMaxWidth().onFocusChanged { onNameFieldFocusChanged(it.isFocused) },
                singleLine = true,
                label = { Text(stringResource(R.string.share_device_name_label)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done,
                    keyboardType = KeyboardType.Text,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSaveDeviceName() }),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onUseSystemDeviceName) {
                    Text(stringResource(R.string.share_device_name_use_system))
                }
                Spacer(Modifier.width(AppSpacing.Small))
                Button(onClick = onSaveDeviceName, enabled = saveNameEnabled) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
fun MemoPreviewCard(content: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = AppShapes.Large,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Medium)) {
            Text(
                stringResource(R.string.share_memo_preview_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.Small))
            Text(
                content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun DeviceNameActionsVisibility(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(tween(DEVICE_NAME_ACTIONS_ENTER_MILLIS)) +
                slideInVertically(initialOffsetY = { -it / 2 }),
        exit =
            fadeOut(tween(DEVICE_NAME_ACTIONS_EXIT_MILLIS)) +
                slideOutVertically(targetOffsetY = { -it / 3 }),
        content = { content() },
    )
}
