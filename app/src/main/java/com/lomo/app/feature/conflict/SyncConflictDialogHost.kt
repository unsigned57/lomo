package com.lomo.app.feature.conflict

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.ui.component.dialog.SyncConflictResolutionDialog

@Composable
fun SyncConflictDialogHost(conflictViewModel: SyncConflictViewModel) {
    val state by conflictViewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        SyncConflictDialogState.Hidden -> {}
        is SyncConflictDialogState.Showing -> {
            SyncConflictResolutionDialog(
                conflictSet = s.conflictSet,
                perFileChoices = s.perFileChoices,
                expandedFilePath = s.expandedFilePath,
                isResolving = s.isResolving,
                onFileChoiceChanged = conflictViewModel::setFileChoice,
                onAllChoicesChanged = conflictViewModel::setAllChoices,
                onAcceptSuggestions = conflictViewModel::acceptSuggestedChoices,
                onToggleExpanded = conflictViewModel::toggleExpandedFile,
                onApply = conflictViewModel::applyResolution,
                onDismiss = conflictViewModel::dismiss,
            )
        }
    }
}
