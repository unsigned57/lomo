package com.lomo.ui.component.dialog

import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewResolutionChoice
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet

/** Callbacks invoked by the sync conflict resolution dialog. */
data class SyncConflictDialogCallbacks(
    val onFileChoiceChanged: (path: String, choice: SyncConflictResolutionChoice) -> Unit,
    val onAllChoicesChanged: (choice: SyncConflictResolutionChoice) -> Unit,
    val onAcceptSuggestions: () -> Unit,
    val onAutoResolveSafeConflicts: () -> Unit,
    val onToggleExpanded: (path: String) -> Unit,
    val onApply: () -> Unit,
    val onDismiss: () -> Unit,
)

/** Callbacks invoked by the sync review resolution dialog. */
data class SyncReviewDialogCallbacks(
    val onItemChoiceChanged: (path: String, choice: SyncReviewResolutionChoice) -> Unit,
    val onAllItemChoicesChanged: (choice: SyncReviewResolutionChoice) -> Unit,
    val onAcceptSuggestions: () -> Unit,
    val onAutoResolveSafeReviews: () -> Unit,
    val onToggleExpanded: (path: String) -> Unit,
    val onApply: () -> Unit,
    val onDismiss: () -> Unit,
)

internal data class ConflictFileListState(
    val files: ImmutableList<SyncConflictFile>,
    val safeChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    val suggestedChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    val perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    val expandedFilePath: String?,
    val reviewMessages: ImmutableMap<String, String>,
    val onFileChoiceChanged: (path: String, choice: SyncConflictResolutionChoice) -> Unit,
    val onToggleExpanded: (path: String) -> Unit,
)

internal data class ReviewFileListState(
    val items: ImmutableList<SyncReviewItem>,
    val safeChoices: ImmutableMap<String, SyncReviewResolutionChoice>,
    val suggestedChoices: ImmutableMap<String, SyncReviewResolutionChoice>,
    val perItemChoices: ImmutableMap<String, SyncReviewResolutionChoice>,
    val blockedPaths: ImmutableSet<String>,
    val expandedFilePath: String?,
    val onItemChoiceChanged: (path: String, choice: SyncReviewResolutionChoice) -> Unit,
    val onToggleExpanded: (path: String) -> Unit,
)

internal data class ConflictFileCardState(
    val choice: SyncConflictResolutionChoice?,
    val suggestedChoice: SyncConflictResolutionChoice?,
    val supportsSkip: Boolean,
    val isExpanded: Boolean,
    val reviewMessage: String?,
    val onChoiceChanged: (SyncConflictResolutionChoice) -> Unit,
    val onToggleExpanded: () -> Unit,
)
