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
import com.lomo.app.feature.update.AppUpdateViewModel
import com.lomo.app.navigation.LomoNavHost
import com.lomo.domain.repository.LanShareService
import com.lomo.ui.component.markdown.MarkdownRenderer

@Composable
fun LomoAppRoot(
    viewModel: MainViewModel,
    shareServiceManager: LanShareService,
    appUpdateViewModel: AppUpdateViewModel = hiltViewModel(),
) {
    val updateDialogState by appUpdateViewModel.dialogState.collectAsStateWithLifecycle()
    updateDialogState?.let { dialogState ->
        val context = LocalContext.current
        val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
        val updateVersion = dialogState.version
        val updateUrl = dialogState.url
        val updateReleaseNotes = dialogState.releaseNotes
        val updateMessage =
            if (updateVersion.isBlank()) {
                stringResource(com.lomo.app.R.string.update_dialog_message)
            } else {
                stringResource(com.lomo.app.R.string.update_dialog_message_with_version, updateVersion)
            }
        val releaseNotes = updateReleaseNotes.takeIf { it.isNotBlank() }

        AlertDialog(
            onDismissRequest = { appUpdateViewModel.dismissUpdateDialog() },
            title = {
                Text(
                    stringResource(com.lomo.app.R.string.update_dialog_title),
                )
            },
            text = {
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
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic.medium()
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(updateUrl))
                        context.startActivity(intent)
                        appUpdateViewModel.dismissUpdateDialog()
                    },
                ) {
                    Text(
                        stringResource(com.lomo.app.R.string.action_download),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        haptic.medium()
                        appUpdateViewModel.dismissUpdateDialog()
                    },
                ) {
                    Text(
                        stringResource(com.lomo.app.R.string.action_cancel),
                    )
                }
            },
        )
    }

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
        val currentIncoming = incomingShare
        if (currentIncoming is com.lomo.domain.model.IncomingShareState.Pending) {
            val payload = currentIncoming.payload
            val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

            AlertDialog(
                onDismissRequest = { shareServiceManager.rejectIncoming() },
                title = { Text(stringResource(com.lomo.app.R.string.share_incoming_title)) },
                text = {
                    Column {
                        Text(
                            text = stringResource(com.lomo.app.R.string.share_incoming_from, payload.senderName),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(
                            modifier = Modifier.height(8.dp),
                        )
                        Text(
                            text = payload.content,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 8,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (payload.attachments.isNotEmpty()) {
                            Spacer(
                                modifier = Modifier.height(8.dp),
                            )
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
                            shareServiceManager.acceptIncoming()
                        },
                    ) {
                        Text(stringResource(com.lomo.app.R.string.action_accept))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            haptic.medium()
                            shareServiceManager.rejectIncoming()
                        },
                    ) {
                        Text(stringResource(com.lomo.app.R.string.action_reject))
                    }
                },
            )
        }
    }
}
