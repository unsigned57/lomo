package com.lomo.app.feature.conflict

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.ui.component.dialog.SyncConflictResolutionDialog
import com.lomo.ui.component.dialog.SyncReviewResolutionDialog

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

        is SyncConflictDialogState.ReviewShowing -> {
            SyncReviewResolutionDialog(
                reviewSession = s.reviewSession,
                perItemChoices = s.perItemChoices,
                blockedPaths = s.blockedPaths,
                expandedFilePath = s.expandedFilePath,
                isResolving = s.isResolving,
                isInitialImportPreview = s.isInitialImportPreview,
                onItemChoiceChanged = controller.onReviewItemChoiceChanged,
                onAllItemChoicesChanged = controller.onAllReviewItemChoicesChanged,
                onAcceptSuggestions = controller.onAcceptSuggestions,
                onAutoResolveSafeReviews = controller.onAutoResolveSafeConflicts,
                onToggleExpanded = controller.onToggleExpanded,
                onApply = controller.onApply,
                onDismiss = controller.onDismiss,
            )
        }
    }
}
