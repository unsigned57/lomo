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
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.*
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun ConflictFileList(
    source: SyncBackendType,
    files: ImmutableList<SyncConflictFile>,
    safeChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    suggestedChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    expandedFilePath: String?,
    reviewMessages: ImmutableMap<String, String>,
    onFileChoiceChanged: (path: String, choice: SyncConflictResolutionChoice) -> Unit,
    modifier: Modifier = Modifier,
    onToggleExpanded: (path: String) -> Unit,
) {
    val supportsSkip = source.supportsDeferredConflictResolutionUi()
    val autoResolvableFiles =
        remember(files, safeChoices) {
            files.filter { file -> safeChoices.containsKey(file.relativePath) }.toImmutableList()
        }
    val manualFiles =
        remember(files, safeChoices) {
            files.filterNot { file -> safeChoices.containsKey(file.relativePath) }.toImmutableList()
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
                choice = perFileChoices[file.relativePath],
                suggestedChoice = suggestedChoices[file.relativePath],
                supportsSkip = supportsSkip,
                isExpanded = expandedFilePath == file.relativePath,
                reviewMessage = reviewMessages[file.relativePath],
                onChoiceChanged = { choice -> onFileChoiceChanged(file.relativePath, choice) },
                onToggleExpanded = { onToggleExpanded(file.relativePath) },
            )
        }
        conflictFileSection(source, manualFiles, false) { file ->
            ConflictFileCard(
                source = source,
                file = file,
                choice = perFileChoices[file.relativePath],
                suggestedChoice = null,
                supportsSkip = supportsSkip,
                isExpanded = expandedFilePath == file.relativePath,
                reviewMessage = reviewMessages[file.relativePath],
                onChoiceChanged = { choice -> onFileChoiceChanged(file.relativePath, choice) },
                onToggleExpanded = { onToggleExpanded(file.relativePath) },
            )
        }
    }
}

@Composable
internal fun ReviewFileList(
    source: SyncBackendType,
    items: ImmutableList<SyncReviewItem>,
    safeChoices: ImmutableMap<String, SyncReviewResolutionChoice>,
    suggestedChoices: ImmutableMap<String, SyncReviewResolutionChoice>,
    perItemChoices: ImmutableMap<String, SyncReviewResolutionChoice>,
    blockedPaths: ImmutableSet<String>,
    expandedFilePath: String?,
    onItemChoiceChanged: (path: String, choice: SyncReviewResolutionChoice) -> Unit,
    modifier: Modifier = Modifier,
    onToggleExpanded: (path: String) -> Unit,
) {
    val supportsSkip = source.supportsDeferredReviewResolutionUi()
    val autoResolvableItems =
        remember(items, safeChoices, blockedPaths) {
            items
                .filter { item -> item.relativePath !in blockedPaths && safeChoices.containsKey(item.relativePath) }
                .toImmutableList()
        }
    val manualItems =
        remember(items, safeChoices) {
            items.filterNot { item -> safeChoices.containsKey(item.relativePath) }.toImmutableList()
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
                choice = perItemChoices[item.relativePath],
                suggestedChoice = suggestedChoices[item.relativePath],
                supportsSkip = supportsSkip,
                isExpanded = expandedFilePath == item.relativePath,
                onChoiceChanged = { choice -> onItemChoiceChanged(item.relativePath, choice) },
                onToggleExpanded = { onToggleExpanded(item.relativePath) },
            )
        }
        reviewFileSection(manualItems, false) { item ->
            ReviewFileCard(
                source = source,
                item = item,
                choice = perItemChoices[item.relativePath],
                suggestedChoice = suggestedChoices[item.relativePath],
                supportsSkip = supportsSkip,
                isExpanded = expandedFilePath == item.relativePath,
                onChoiceChanged = { choice -> onItemChoiceChanged(item.relativePath, choice) },
                onToggleExpanded = { onToggleExpanded(item.relativePath) },
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
