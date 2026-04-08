package com.lomo.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.lomo.app.feature.update.AppUpdateViewModel
import com.lomo.app.feature.update.LomoAppUpdateDialog
import com.lomo.app.feature.update.LomoAppUpdateProgressDialog
import com.lomo.app.navigation.LomoNavHost
import com.lomo.app.util.injectedHiltViewModel
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.domain.model.IncomingShareState
import com.lomo.domain.repository.LanShareService
import com.lomo.ui.benchmark.benchmarkAnchorRoot

@Composable
fun LomoAppRoot(
    shareServiceManager: LanShareService,
    modifier: Modifier = Modifier,
    appUpdateViewModel: AppUpdateViewModel = injectedHiltViewModel(),
) {
    val updateDialogState by appUpdateViewModel.dialogState.collectAsStateWithLifecycle()
    val progressDialogState by appUpdateViewModel.progressDialogState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    LomoAppUpdateDialog(
        dialogState = updateDialogState,
        onDismiss = appUpdateViewModel::dismissUpdateDialog,
        onStartInAppUpdate = appUpdateViewModel::startInAppUpdate,
        onOpenReleasePage = uriHandler::openUri,
    )
    LomoAppUpdateProgressDialog(
        state = progressDialogState,
        onCancel = appUpdateViewModel::cancelInAppUpdate,
        onRetry = appUpdateViewModel::retryInAppUpdate,
        onOpenInstallPermissionSettings = { context.openInstallPermissionSettings() },
        onOpenReleasePage = uriHandler::openUri,
        onDismiss = appUpdateViewModel::dismissProgressDialog,
    )

    Surface(
        modifier = modifier.fillMaxSize().benchmarkAnchorRoot(BenchmarkAnchorContract.APP_ROOT),
        color = MaterialTheme.colorScheme.background,
    ) {
        val navController = rememberNavController()
        LomoNavHost(navController = navController)

        val incomingShare by shareServiceManager.incomingShare.collectAsStateWithLifecycle()
        IncomingShareDialog(
            incomingShare = incomingShare,
            onAccept = shareServiceManager::acceptIncoming,
            onReject = shareServiceManager::rejectIncoming,
        )
    }
}

private fun Context.openInstallPermissionSettings() {
    val intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
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
