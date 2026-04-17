package com.lomo.app.feature.conflict

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.ui.component.dialog.SyncConflictResolutionDialog

@Composable
fun SyncConflictDialogHost(controller: SyncConflictDialogController) {
    val state by controller.state.collectAsStateWithLifecycle()
    when (val s = state) {
        SyncConflictDialogState.Hidden -> {}
        is SyncConflictDialogState.Showing -> {
            SyncConflictResolutionDialog(
                conflictSet = s.conflictSet,
                perFileChoices = s.perFileChoices,
                expandedFilePath = s.expandedFilePath,
                isResolving = s.isResolving,
                onFileChoiceChanged = controller.onFileChoiceChanged,
                onAllChoicesChanged = controller.onAllChoicesChanged,
                onAcceptSuggestions = controller.onAcceptSuggestions,
                onAutoResolveSafeConflicts = controller.onAutoResolveSafeConflicts,
                onToggleExpanded = controller.onToggleExpanded,
                onApply = controller.onApply,
                onDismiss = controller.onDismiss,
            )
        }
    }
}
