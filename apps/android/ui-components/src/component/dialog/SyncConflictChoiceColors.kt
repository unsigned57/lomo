package com.lomo.ui.component.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncReviewResolutionChoice

@Composable
internal fun SyncConflictResolutionChoice.selectedContainerColor() =
    when (this) {
        SyncConflictResolutionChoice.KEEP_LOCAL -> MaterialTheme.colorScheme.primaryContainer
        SyncConflictResolutionChoice.KEEP_REMOTE -> MaterialTheme.colorScheme.secondaryContainer
        SyncConflictResolutionChoice.MERGE_TEXT -> MaterialTheme.colorScheme.tertiaryContainer
        SyncConflictResolutionChoice.SKIP_FOR_NOW -> MaterialTheme.colorScheme.errorContainer
    }

@Composable
internal fun SyncConflictResolutionChoice.selectedContentColor() =
    when (this) {
        SyncConflictResolutionChoice.KEEP_LOCAL -> MaterialTheme.colorScheme.onPrimaryContainer
        SyncConflictResolutionChoice.KEEP_REMOTE -> MaterialTheme.colorScheme.onSecondaryContainer
        SyncConflictResolutionChoice.MERGE_TEXT -> MaterialTheme.colorScheme.onTertiaryContainer
        SyncConflictResolutionChoice.SKIP_FOR_NOW -> MaterialTheme.colorScheme.onErrorContainer
    }

@Composable
internal fun SyncReviewResolutionChoice.selectedContainerColor() =
    when (this) {
        SyncReviewResolutionChoice.KEEP_LOCAL -> MaterialTheme.colorScheme.primaryContainer
        SyncReviewResolutionChoice.KEEP_INCOMING -> MaterialTheme.colorScheme.secondaryContainer
        SyncReviewResolutionChoice.MERGE_TEXT -> MaterialTheme.colorScheme.tertiaryContainer
        SyncReviewResolutionChoice.SKIP_FOR_NOW -> MaterialTheme.colorScheme.errorContainer
    }

@Composable
internal fun SyncReviewResolutionChoice.selectedContentColor() =
    when (this) {
        SyncReviewResolutionChoice.KEEP_LOCAL -> MaterialTheme.colorScheme.onPrimaryContainer
        SyncReviewResolutionChoice.KEEP_INCOMING -> MaterialTheme.colorScheme.onSecondaryContainer
        SyncReviewResolutionChoice.MERGE_TEXT -> MaterialTheme.colorScheme.onTertiaryContainer
        SyncReviewResolutionChoice.SKIP_FOR_NOW -> MaterialTheme.colorScheme.onErrorContainer
    }
