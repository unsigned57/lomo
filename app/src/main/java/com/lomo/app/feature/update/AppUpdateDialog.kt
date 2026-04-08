package com.lomo.app.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.util.LocalAppHapticFeedback

@Composable
fun LomoAppUpdateDialog(
    dialogState: AppUpdateDialogState?,
    onDismiss: () -> Unit,
    onStartInAppUpdate: (AppUpdateDialogState) -> Unit,
    onOpenReleasePage: (String) -> Unit,
) {
    dialogState ?: return

    val haptic = LocalAppHapticFeedback.current
    val updateMessage =
        if (dialogState.version.isBlank()) {
            stringResource(R.string.update_dialog_message)
        } else {
            stringResource(R.string.update_dialog_message_with_version, dialogState.version)
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            UpdateDialogTextContent(
                updateMessage = updateMessage,
                releaseNotes = dialogState.releaseNotes.takeIf { it.isNotBlank() },
            )
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        haptic.medium()
                        onOpenReleasePage(dialogState.url)
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.action_github_download))
                }
                TextButton(
                    onClick = {
                        haptic.medium()
                        if (dialogState.canDownloadInApp) {
                            onStartInAppUpdate(dialogState)
                        } else {
                            onOpenReleasePage(dialogState.url)
                        }
                        onDismiss()
                    },
                ) {
                    Text(
                        if (dialogState.canDownloadInApp) {
                            stringResource(R.string.action_download)
                        } else {
                            stringResource(R.string.action_open_download_page)
                        },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    haptic.medium()
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private val AppUpdateDialogState.canDownloadInApp: Boolean
    get() = debugSimulationScenario != null || !apkDownloadUrl.isNullOrBlank()

@Composable
private fun UpdateDialogTextContent(
    updateMessage: String,
    releaseNotes: String?,
) {
    Column(
        modifier =
            Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Text(updateMessage)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.update_dialog_release_notes),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (releaseNotes.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.update_dialog_release_notes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MarkdownRenderer(
                content = releaseNotes,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
