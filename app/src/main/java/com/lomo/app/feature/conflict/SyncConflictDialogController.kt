package com.lomo.app.feature.conflict

import kotlinx.coroutines.flow.StateFlow

data class SyncConflictDialogController(
    val state: StateFlow<SyncConflictDialogState>,
    val onFileChoiceChanged: (String, com.lomo.domain.model.SyncConflictResolutionChoice) -> Unit,
    val onAllChoicesChanged: (com.lomo.domain.model.SyncConflictResolutionChoice) -> Unit,
    val onAcceptSuggestions: () -> Unit,
    val onAutoResolveSafeConflicts: () -> Unit,
    val onToggleExpanded: (String) -> Unit,
    val onApply: () -> Unit,
    val onDismiss: () -> Unit,
    val onShowConflictDialog: (com.lomo.domain.model.SyncConflictSet) -> Unit,
)
