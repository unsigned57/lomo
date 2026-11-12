package com.lomo.app.feature.conflict

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.dialog.SyncConflictDialogCallbacks
import com.lomo.ui.component.dialog.SyncConflictResolutionDialog
import com.lomo.ui.component.dialog.SyncReviewDialogCallbacks
import com.lomo.ui.component.dialog.SyncReviewResolutionDialog

@Composable
fun SyncConflictDialogHost(controller: SyncConflictDialogController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsStateWithLifecycle()
    when (val s = state) {
        SyncConflictDialogState.Hidden -> {}

        is SyncConflictDialogState.Showing -> {
            SyncConflictResolutionDialog(
                // The dialog owns a separate a11y window — the root flag must be set on
                // its own subtree for the tag to surface as a resource id.
                modifier = modifier.benchmarkAnchorRoot(BenchmarkAnchorContract.SYNC_CONFLICT_ROOT),
                conflictSet = s.conflictSet,
                perFileChoices = s.perFileChoices,
                expandedFilePath = s.expandedFilePath,
                isResolving = s.isResolving,
                callbacks =
                SyncConflictDialogCallbacks(
                    onFileChoiceChanged = controller.onFileChoiceChanged,
                    onAllChoicesChanged = controller.onAllChoicesChanged,
                    onAcceptSuggestions = controller.onAcceptSuggestions,
                    onAutoResolveSafeConflicts = controller.onAutoResolveSafeConflicts,
                    onToggleExpanded = controller.onToggleExpanded,
                    onApply = controller.onApply,
                    onDismiss = controller.onDismiss,
                ),
            )
        }

        is SyncConflictDialogState.ReviewShowing -> {
            SyncReviewResolutionDialog(
                modifier = modifier,
                reviewSession = s.reviewSession,
                perItemChoices = s.perItemChoices,
                blockedPaths = s.blockedPaths,
                expandedFilePath = s.expandedFilePath,
                isResolving = s.isResolving,
                isInitialImportPreview = s.isInitialImportPreview,
                callbacks =
                SyncReviewDialogCallbacks(
                    onItemChoiceChanged = controller.onReviewItemChoiceChanged,
                    onAllItemChoicesChanged = controller.onAllReviewItemChoicesChanged,
                    onAcceptSuggestions = controller.onAcceptSuggestions,
                    onAutoResolveSafeReviews = controller.onAutoResolveSafeConflicts,
                    onToggleExpanded = controller.onToggleExpanded,
                    onApply = controller.onApply,
                    onDismiss = controller.onDismiss,
                ),
            )
        }
    }
}
