package com.lomo.app.feature.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.LanIncomingBatch
import com.lomo.domain.model.LanPairingRequest
import com.lomo.ui.theme.AppSpacing

private const val BYTES_PER_KIBIBYTE = 1_024L
private const val BYTES_PER_MEBIBYTE = 1_048_576L

@Composable
internal fun LanPairingConfirmationDialog(
    request: LanPairingRequest?,
    onConfirm: (String) -> Unit,
    onDecline: (String) -> Unit,
) {
    if (request == null) return
    AlertDialog(
        onDismissRequest = { onDecline(request.pairingId) },
        title = { Text(stringResource(R.string.lan_pairing_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall)) {
                Text(request.peerDisplayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    request.shortCode,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.lan_pairing_confirm_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(request.pairingId) }) {
                Text(stringResource(R.string.lan_pairing_code_matches))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDecline(request.pairingId) }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun LanBatchApprovalDialog(
    batch: LanIncomingBatch?,
    onApprove: (String, String) -> Unit,
    onReject: (String, String) -> Unit,
) {
    if (batch == null) return
    AlertDialog(
        onDismissRequest = { onReject(batch.sessionId, batch.batchId) },
        title = { Text(stringResource(R.string.lan_batch_approval_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                Text(batch.senderDisplayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.lan_batch_approval_summary,
                        batch.itemCount,
                        batch.attachmentCount,
                        formatLanBytes(batch.totalBytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                batch.titles.forEach { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (batch.items.isNotEmpty()) {
                    Text(
                        stringResource(R.string.lan_batch_item_results, batch.items.size),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApprove(batch.sessionId, batch.batchId) }) {
                Text(stringResource(R.string.action_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = { onReject(batch.sessionId, batch.batchId) }) {
                Text(stringResource(R.string.action_reject))
            }
        },
    )
}

private fun formatLanBytes(bytes: Long): String =
    when {
        bytes >= BYTES_PER_MEBIBYTE -> "${bytes / BYTES_PER_MEBIBYTE} MB"
        bytes >= BYTES_PER_KIBIBYTE -> "${bytes / BYTES_PER_KIBIBYTE} KB"
        else -> "$bytes B"
    }
