package com.lomo.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncReviewItem
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.sync_conflict_section_attention
import com.lomo.ui.generated.resources.sync_conflict_section_auto
import com.lomo.ui.generated.resources.sync_conflict_section_manual
import com.lomo.ui.generated.resources.sync_conflict_section_ready
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun ConflictFileList(
    source: SyncBackendType,
    state: ConflictFileListState,
    modifier: Modifier = Modifier,
) {
    val supportsSkip = source.supportsDeferredConflictResolutionUi()
    val autoResolvableFiles =
        remember(state.files, state.safeChoices) {
            state.files.filter { file -> state.safeChoices.containsKey(file.relativePath) }.toImmutableList()
        }
    val manualFiles =
        remember(state.files, state.safeChoices) {
            state.files.filterNot { file -> state.safeChoices.containsKey(file.relativePath) }.toImmutableList()
        }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = conflictListPadding(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
    ) {
        conflictFileSection(source, autoResolvableFiles, true) { file ->
            ConflictFileCard(
                source = source,
                file = file,
                state =
                    ConflictFileCardState(
                        choice = state.perFileChoices[file.relativePath],
                        suggestedChoice = state.suggestedChoices[file.relativePath],
                        supportsSkip = supportsSkip,
                        isExpanded = state.expandedFilePath == file.relativePath,
                        reviewMessage = state.reviewMessages[file.relativePath],
                        onChoiceChanged = { choice -> state.onFileChoiceChanged(file.relativePath, choice) },
                        onToggleExpanded = { state.onToggleExpanded(file.relativePath) },
                    ),
            )
        }
        conflictFileSection(source, manualFiles, false) { file ->
            ConflictFileCard(
                source = source,
                file = file,
                state =
                    ConflictFileCardState(
                        choice = state.perFileChoices[file.relativePath],
                        suggestedChoice = null,
                        supportsSkip = supportsSkip,
                        isExpanded = state.expandedFilePath == file.relativePath,
                        reviewMessage = state.reviewMessages[file.relativePath],
                        onChoiceChanged = { choice -> state.onFileChoiceChanged(file.relativePath, choice) },
                        onToggleExpanded = { state.onToggleExpanded(file.relativePath) },
                    ),
            )
        }
    }
}

@Composable
internal fun ReviewFileList(
    source: SyncBackendType,
    state: ReviewFileListState,
    modifier: Modifier = Modifier,
) {
    val supportsSkip = source.supportsDeferredReviewResolutionUi()
    val autoResolvableItems =
        remember(state.items, state.safeChoices, state.blockedPaths) {
            state.items
                .filter { item ->
                    item.relativePath !in state.blockedPaths && state.safeChoices.containsKey(item.relativePath)
                }
                .toImmutableList()
        }
    val manualItems =
        remember(state.items, state.safeChoices) {
            state.items.filterNot { item -> state.safeChoices.containsKey(item.relativePath) }.toImmutableList()
        }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = conflictListPadding(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
    ) {
        reviewFileSection(autoResolvableItems, true) { item ->
            ReviewFileCard(
                source = source,
                item = item,
                choice = state.perItemChoices[item.relativePath],
                suggestedChoice = state.suggestedChoices[item.relativePath],
                supportsSkip = supportsSkip,
                isExpanded = state.expandedFilePath == item.relativePath,
                onChoiceChanged = { choice -> state.onItemChoiceChanged(item.relativePath, choice) },
                onToggleExpanded = { state.onToggleExpanded(item.relativePath) },
            )
        }
        reviewFileSection(manualItems, false) { item ->
            ReviewFileCard(
                source = source,
                item = item,
                choice = state.perItemChoices[item.relativePath],
                suggestedChoice = state.suggestedChoices[item.relativePath],
                supportsSkip = supportsSkip,
                isExpanded = state.expandedFilePath == item.relativePath,
                onChoiceChanged = { choice -> state.onItemChoiceChanged(item.relativePath, choice) },
                onToggleExpanded = { state.onToggleExpanded(item.relativePath) },
            )
        }
    }
}

private fun conflictListPadding(): PaddingValues =
    PaddingValues(
        start = AppSpacing.ScreenHorizontalPadding,
        end = AppSpacing.ScreenHorizontalPadding,
        top = AppSpacing.Small,
        bottom = AppSpacing.ExtraLarge + AppSpacing.ExtraLarge + AppSpacing.ExtraLarge,
    )

private fun androidx.compose.foundation.lazy.LazyListScope.conflictFileSection(
    source: SyncBackendType,
    files: ImmutableList<SyncConflictFile>,
    autoResolvable: Boolean,
    itemContent: @Composable (SyncConflictFile) -> Unit,
) {
    if (files.isEmpty()) return
    item(key = if (autoResolvable) "auto-resolvable-header" else "manual-header") {
        ConflictSectionHeader(
            text = stringResource(conflictSectionTitle(source, autoResolvable)),
            count = files.size,
        )
    }
    items(items = files, key = { it.relativePath }) { file -> itemContent(file) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.reviewFileSection(
    items: ImmutableList<SyncReviewItem>,
    autoResolvable: Boolean,
    itemContent: @Composable (SyncReviewItem) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = if (autoResolvable) "review-auto-resolvable-header" else "review-manual-header") {
        ConflictSectionHeader(
            text =
                stringResource(
                    if (autoResolvable) {
                        Res.string.sync_conflict_section_ready
                    } else {
                        Res.string.sync_conflict_section_attention
                    },
                ),
            count = items.size,
        )
    }
    items(items = items, key = { it.relativePath }) { item -> itemContent(item) }
}

private fun conflictSectionTitle(
    source: SyncBackendType,
    autoResolvable: Boolean,
): StringResource =
    if (autoResolvable) {
        if (source == SyncBackendType.INBOX) {
            Res.string.sync_conflict_section_ready
        } else {
            Res.string.sync_conflict_section_auto
        }
    } else {
        if (source == SyncBackendType.INBOX) {
            Res.string.sync_conflict_section_attention
        } else {
            Res.string.sync_conflict_section_manual
        }
    }
