package com.lomo.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.share.LanBatchApprovalDialog
import com.lomo.app.feature.share.LanPairingConfirmationDialog
import com.lomo.app.feature.update.AppUpdateViewModel
import com.lomo.app.feature.update.LomoAppUpdateDialog
import com.lomo.app.feature.update.LomoAppUpdateProgressDialog
import com.lomo.app.navigation.LomoNavHost
import com.lomo.app.util.injectedKoinViewModel
import com.lomo.domain.repository.LanShareService
import com.lomo.ui.benchmark.benchmarkAnchorRoot

@Composable
fun LomoAppRoot(
    shareServiceManager: LanShareService,
    modifier: Modifier = Modifier,
    foregroundEntryId: Long = 0L,
    suppressForegroundAutoInput: Boolean = false,
    appUpdateViewModel: AppUpdateViewModel = injectedKoinViewModel(),
) {
    val updateDialogState by appUpdateViewModel.dialogState.collectAsStateWithLifecycle()
    val progressDialogState by appUpdateViewModel.progressDialogState.collectAsStateWithLifecycle()
    val incomingBatch by shareServiceManager.incomingBatch.collectAsStateWithLifecycle()
    val pendingPairing by shareServiceManager.pendingPairing.collectAsStateWithLifecycle()
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
        LomoNavHost(
            navController = rememberNavController(),
            foregroundEntryId = foregroundEntryId,
            suppressForegroundAutoInput = suppressForegroundAutoInput,
        )
        LanPairingConfirmationDialog(
            request = pendingPairing,
            onConfirm = shareServiceManager::confirmPairing,
            onDecline = shareServiceManager::declinePairing,
        )
        LanBatchApprovalDialog(
            batch = incomingBatch,
            onApprove = shareServiceManager::approveIncoming,
            onReject = shareServiceManager::rejectIncoming,
        )
    }
}

private fun Context.openInstallPermissionSettings() {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
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
