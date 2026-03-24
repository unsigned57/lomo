package com.lomo.app

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.app.feature.update.AppUpdateViewModel
import com.lomo.app.navigation.LomoNavHost
import com.lomo.domain.model.IncomingShareState
import com.lomo.domain.repository.LanShareService
import com.lomo.ui.component.markdown.MarkdownRenderer

@Composable
fun LomoAppRoot(
    viewModel: MainViewModel,
    shareServiceManager: LanShareService,
    appUpdateViewModel: AppUpdateViewModel = hiltViewModel(),
) {
    val updateDialogState by appUpdateViewModel.dialogState.collectAsStateWithLifecycle()
    LomoAppUpdateDialog(
        dialogState = updateDialogState,
        onDismiss = appUpdateViewModel::dismissUpdateDialog,
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        val navController = rememberNavController()
        LomoNavHost(
            navController = navController,
            viewModel = viewModel,
        )

        val incomingShare by shareServiceManager.incomingShare.collectAsStateWithLifecycle()
        IncomingShareDialog(
            incomingShare = incomingShare,
            onAccept = shareServiceManager::acceptIncoming,
            onReject = shareServiceManager::rejectIncoming,
        )
    }
}

@Composable
private fun LomoAppUpdateDialog(
    dialogState: AppUpdateDialogState?,
    onDismiss: () -> Unit,
) {
    dialogState ?: return

    val context = LocalContext.current
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val updateMessage =
        if (dialogState.version.isBlank()) {
            stringResource(com.lomo.app.R.string.update_dialog_message)
        } else {
            stringResource(com.lomo.app.R.string.update_dialog_message_with_version, dialogState.version)
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(com.lomo.app.R.string.update_dialog_title))
        },
        text = {
            UpdateDialogTextContent(
                updateMessage = updateMessage,
                releaseNotes = dialogState.releaseNotes.takeIf { it.isNotBlank() },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptic.medium()
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(dialogState.url))
                    context.startActivity(intent)
                    onDismiss()
                },
            ) {
                Text(stringResource(com.lomo.app.R.string.action_download))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    haptic.medium()
                    onDismiss()
                },
            ) {
                Text(stringResource(com.lomo.app.R.string.action_cancel))
            }
        },
    )
}

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
            text = stringResource(com.lomo.app.R.string.update_dialog_release_notes),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (releaseNotes.isNullOrBlank()) {
            Text(
                text = stringResource(com.lomo.app.R.string.update_dialog_release_notes_empty),
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

@Composable
private fun IncomingShareDialog(
    incomingShare: IncomingShareState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val pendingShare = incomingShare as? IncomingShareState.Pending ?: return
    val payload = pendingShare.payload
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(com.lomo.app.R.string.share_incoming_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(com.lomo.app.R.string.share_incoming_from, payload.senderName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = payload.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 8,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (payload.attachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text =
                            pluralStringResource(
                                com.lomo.app.R.plurals.share_incoming_attachments_count,
                                payload.attachments.size,
                                payload.attachments.size,
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptic.medium()
                    onAccept()
                },
            ) {
                Text(stringResource(com.lomo.app.R.string.action_accept))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    haptic.medium()
                    onReject()
                },
            ) {
                Text(stringResource(com.lomo.app.R.string.action_reject))
            }
        },
    )
}
