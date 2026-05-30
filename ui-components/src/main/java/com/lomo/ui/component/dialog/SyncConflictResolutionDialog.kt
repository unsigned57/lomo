package com.lomo.ui.component.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lomo.domain.model.SyncConflictAutoResolutionAdvisor
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewAutoResolutionAdvisor
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.SyncReviewSession
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncConflictResolutionDialog(
    conflictSet: SyncConflictSet,
    perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    expandedFilePath: String?,
    isResolving: Boolean,
    onFileChoiceChanged: (path: String, choice: SyncConflictResolutionChoice) -> Unit,
    onAllChoicesChanged: (choice: SyncConflictResolutionChoice) -> Unit,
    onAcceptSuggestions: () -> Unit,
    onAutoResolveSafeConflicts: () -> Unit,
    onToggleExpanded: (path: String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isInitialImportPreview: Boolean = false,
    reviewMessages: ImmutableMap<String, String> = emptyMap<String, String>().toImmutableMap(),
) {
    val conflictFiles = remember(conflictSet.files) { conflictSet.files.toImmutableList() }
    val safeChoices =
        remember(conflictSet.files) {
            conflictSet.files
                .mapNotNull { file ->
                    SyncConflictAutoResolutionAdvisor.safeAutoResolutionChoice(file)?.let { choice ->
                        file.relativePath to choice
                    }
                }.toMap().toImmutableMap()
        }
    val suggestedChoices =
        remember(conflictSet.files) {
            conflictSet.files
                .mapNotNull { file ->
                    SyncConflictAutoResolutionAdvisor.suggestedChoice(file)?.let { choice ->
                        file.relativePath to choice
                    }
                }.toMap().toImmutableMap()
        }
    val canAutoResolveSafe =
        remember(conflictSet.source, conflictSet.files, safeChoices) {
            safeChoices.isNotEmpty() &&
                (conflictSet.source.supportsDeferredConflictResolutionUi() ||
                    safeChoices.size == conflictSet.files.size)
        }
    val allFilesChosen = conflictSet.files.all { file ->
        perFileChoices.containsKey(file.relativePath)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopBar(
                        source = conflictSet.source,
                        isInitialImportPreview = isInitialImportPreview,
                        onDismiss = onDismiss,
                    )
                },
                bottomBar = {
                    BottomSection(
                        allFilesChosen = allFilesChosen,
                        onApply = onApply,
                    )
                },
                containerColor = MaterialTheme.colorScheme.background,
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    GlobalActionRow(
                        conflictSet = conflictSet,
                        canAutoResolveSafe = canAutoResolveSafe,
                        perFileChoices = perFileChoices,
                        isInitialImportPreview = isInitialImportPreview,
                        fileCount = conflictSet.files.size,
                        onAllChoicesChanged = onAllChoicesChanged,
                        onAcceptSuggestions = onAcceptSuggestions,
                        onAutoResolveSafeConflicts = onAutoResolveSafeConflicts,
                    )
                    ConflictFileList(
                        source = conflictSet.source,
                        files = conflictFiles,
                        safeChoices = safeChoices,
                        suggestedChoices = suggestedChoices,
                        perFileChoices = perFileChoices,
                        expandedFilePath = expandedFilePath,
                        reviewMessages = reviewMessages,
                        onFileChoiceChanged = onFileChoiceChanged,
                        modifier = Modifier.weight(1f),
                        onToggleExpanded = onToggleExpanded,
                    )
                }
            }

            AnimatedVisibility(
                visible = isResolving,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ResolvingOverlay()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncReviewResolutionDialog(
    reviewSession: SyncReviewSession,
    perItemChoices: ImmutableMap<String, SyncReviewResolutionChoice>,
    blockedPaths: ImmutableSet<String>,
    expandedFilePath: String?,
    isResolving: Boolean,
    onItemChoiceChanged: (path: String, choice: SyncReviewResolutionChoice) -> Unit,
    onAllItemChoicesChanged: (choice: SyncReviewResolutionChoice) -> Unit,
    onAcceptSuggestions: () -> Unit,
    onAutoResolveSafeReviews: () -> Unit,
    onToggleExpanded: (path: String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isInitialImportPreview: Boolean = false,
) {
    val reviewItems = remember(reviewSession.items) { reviewSession.items.toImmutableList() }
    val safeChoices =
        remember(reviewSession.items, blockedPaths) {
            reviewSession.items
                .filterNot { item -> item.relativePath in blockedPaths }
                .mapNotNull { item ->
                    SyncReviewAutoResolutionAdvisor.safeAutoResolutionChoice(item)?.let { choice ->
                        item.relativePath to choice
                    }
                }.toMap().toImmutableMap()
        }
    val suggestedChoices =
        remember(reviewSession.items, blockedPaths) {
            reviewSession.items
                .filterNot { item -> item.relativePath in blockedPaths }
                .mapNotNull { item ->
                    SyncReviewAutoResolutionAdvisor.suggestedChoice(item)?.let { choice ->
                        item.relativePath to choice
                    }
                }.toMap().toImmutableMap()
        }
    val selectableCount =
        remember(reviewSession.items, blockedPaths) {
            reviewSession.items.count { item -> item.relativePath !in blockedPaths }
        }
    val canAutoResolveSafe =
        remember(reviewSession.source, selectableCount, safeChoices) {
            safeChoices.isNotEmpty() &&
                (reviewSession.source.supportsDeferredReviewResolutionUi() || safeChoices.size == selectableCount)
        }
    val allItemsChosen =
        reviewSession.items
            .filterNot { item -> item.relativePath in blockedPaths }
            .all { item -> perItemChoices.containsKey(item.relativePath) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopBar(
                        source = reviewSession.source,
                        isInitialImportPreview = isInitialImportPreview,
                        onDismiss = onDismiss,
                    )
                },
                bottomBar = {
                    BottomSection(
                        allFilesChosen = allItemsChosen,
                        onApply = onApply,
                    )
                },
                containerColor = MaterialTheme.colorScheme.background,
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    ReviewGlobalActionRow(
                        source = reviewSession.source,
                        canAutoResolveSafe = canAutoResolveSafe,
                        perItemChoices = perItemChoices,
                        isInitialImportPreview = isInitialImportPreview,
                        itemCount = selectableCount,
                        onAllItemChoicesChanged = onAllItemChoicesChanged,
                        onAcceptSuggestions = onAcceptSuggestions,
                        onAutoResolveSafeReviews = onAutoResolveSafeReviews,
                    )
                    ReviewFileList(
                        source = reviewSession.source,
                        items = reviewItems,
                        safeChoices = safeChoices,
                        suggestedChoices = suggestedChoices,
                        perItemChoices = perItemChoices,
                        blockedPaths = blockedPaths,
                        expandedFilePath = expandedFilePath,
                        onItemChoiceChanged = onItemChoiceChanged,
                        modifier = Modifier.weight(1f),
                        onToggleExpanded = onToggleExpanded,
                    )
                }
            }

            AnimatedVisibility(
                visible = isResolving,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ResolvingOverlay()
            }
        }
    }
}
