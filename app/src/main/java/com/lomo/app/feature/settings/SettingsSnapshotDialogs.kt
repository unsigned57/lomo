package com.lomo.app.feature.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R

@Composable
internal fun SnapshotPreferenceDialogs(
    uiState: SettingsScreenUiState,
    snapshotFeature: SettingsSnapshotFeatureViewModel,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    val memoCountLabels =
        options.snapshotRetentionCounts.associateWith { count ->
            memoSnapshotCountOptionLabel(count)
        }
    val ageLabels =
        options.snapshotRetentionDays.associateWith { days ->
            snapshotAgeOptionLabel(days)
        }
    SelectionDialogIfVisible(
        visible = dialogState.showMemoSnapshotCountDialog,
        title = stringResource(R.string.settings_memo_snapshot_count_dialog_title),
        options = options.snapshotRetentionCounts,
        currentSelection = uiState.snapshot.memoSnapshotMaxCount,
        onDismiss = { dialogState.showMemoSnapshotCountDialog = false },
        onSelect = {
            snapshotFeature.updateMemoSnapshotMaxCount(it)
            dialogState.showMemoSnapshotCountDialog = false
        },
        labelProvider = { count -> memoCountLabels[count] ?: count.toString() },
    )
    SelectionDialogIfVisible(
        visible = dialogState.showMemoSnapshotAgeDialog,
        title = stringResource(R.string.settings_memo_snapshot_age_dialog_title),
        options = options.snapshotRetentionDays,
        currentSelection = uiState.snapshot.memoSnapshotMaxAgeDays,
        onDismiss = { dialogState.showMemoSnapshotAgeDialog = false },
        onSelect = {
            snapshotFeature.updateMemoSnapshotMaxAgeDays(it)
            dialogState.showMemoSnapshotAgeDialog = false
        },
        labelProvider = { days -> ageLabels[days] ?: days.toString() },
    )
    SnapshotDisableConfirmDialog(
        snapshotFeature = snapshotFeature,
        dialogState = dialogState,
    )
}

@Composable
private fun SnapshotDisableConfirmDialog(
    snapshotFeature: SettingsSnapshotFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    val target = dialogState.pendingSnapshotDisableTarget ?: return
    AlertDialog(
        onDismissRequest = { dialogState.pendingSnapshotDisableTarget = null },
        title = { Text(stringResource(R.string.settings_memo_snapshot_disable_title)) },
        text = { Text(stringResource(R.string.settings_memo_snapshot_disable_message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    if (target == SettingsSnapshotDisableTarget.MEMO) {
                        snapshotFeature.updateMemoSnapshotsEnabled(false)
                    }
                    dialogState.pendingSnapshotDisableTarget = null
                },
            ) {
                Text(stringResource(R.string.action_turn_off_and_clear))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.pendingSnapshotDisableTarget = null }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
