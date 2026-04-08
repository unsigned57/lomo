package com.lomo.ui.component.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.SyncConflictTextMerge
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSessionKind
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SimpleLineDiff
import com.lomo.ui.R
import com.lomo.ui.component.diff.DiffViewer
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList

@Composable
fun SyncConflictResolutionDialog(
    conflictSet: SyncConflictSet,
    perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    expandedFilePath: String?,
    isResolving: Boolean,
    onFileChoiceChanged: (path: String, choice: SyncConflictResolutionChoice) -> Unit,
    onAllChoicesChanged: (choice: SyncConflictResolutionChoice) -> Unit,
    onAcceptSuggestions: () -> Unit,
    onToggleExpanded: (path: String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val conflictFiles = remember(conflictSet.files) { conflictSet.files.toImmutableList() }
    val allFilesChosen = conflictSet.files.all { file ->
        perFileChoices.containsKey(file.relativePath)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ConflictDialogContent(
                    conflictSet = conflictSet,
                    perFileChoices = perFileChoices,
                    expandedFilePath = expandedFilePath,
                    allFilesChosen = allFilesChosen,
                    onAllChoicesChanged = onAllChoicesChanged,
                    onAcceptSuggestions = onAcceptSuggestions,
                    onFileChoiceChanged = onFileChoiceChanged,
                    onToggleExpanded = onToggleExpanded,
                    conflictFiles = conflictFiles,
                    onApply = onApply,
                    onDismiss = onDismiss,
                )
                if (isResolving) {
                    ResolvingOverlay()
                }
            }
        }
    }
}

@Composable
private fun ConflictDialogContent(
    conflictSet: SyncConflictSet,
    perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    expandedFilePath: String?,
    allFilesChosen: Boolean,
    onAllChoicesChanged: (SyncConflictResolutionChoice) -> Unit,
    onAcceptSuggestions: () -> Unit,
    onFileChoiceChanged: (path: String, choice: SyncConflictResolutionChoice) -> Unit,
    onToggleExpanded: (path: String) -> Unit,
    conflictFiles: ImmutableList<SyncConflictFile>,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            sessionKind = conflictSet.sessionKind,
            fileCount = conflictSet.files.size,
            onDismiss = onDismiss,
        )
        GlobalActionRow(
            conflictSet = conflictSet,
            onAllChoicesChanged = onAllChoicesChanged,
            onAcceptSuggestions = onAcceptSuggestions,
            modifier = Modifier.padding(horizontal = AppSpacing.ScreenHorizontalPadding),
        )
        Spacer(modifier = Modifier.height(AppSpacing.Small))
        ConflictFileList(
            source = conflictSet.source,
            files = conflictFiles,
            perFileChoices = perFileChoices,
            expandedFilePath = expandedFilePath,
            onFileChoiceChanged = onFileChoiceChanged,
            onToggleExpanded = onToggleExpanded,
        )
        BottomSection(
            allFilesChosen = allFilesChosen,
            onApply = onApply,
        )
    }
}

@Composable
private fun ColumnScope.ConflictFileList(
    source: com.lomo.domain.model.SyncBackendType,
    files: ImmutableList<SyncConflictFile>,
    perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    expandedFilePath: String?,
    onFileChoiceChanged: (path: String, choice: SyncConflictResolutionChoice) -> Unit,
    onToggleExpanded: (path: String) -> Unit,
) {
    val supportsSkip =
        source == com.lomo.domain.model.SyncBackendType.S3 ||
            source == com.lomo.domain.model.SyncBackendType.WEBDAV
    LazyColumn(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.ScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        items(
            items = files,
            key = { it.relativePath },
        ) { file ->
            ConflictFileCard(
                file = file,
                choice = perFileChoices[file.relativePath],
                supportsSkip = supportsSkip,
                isExpanded = expandedFilePath == file.relativePath,
                onChoiceChanged = { choice ->
                    onFileChoiceChanged(file.relativePath, choice)
                },
                onToggleExpanded = { onToggleExpanded(file.relativePath) },
            )
        }
    }
}

@Composable
private fun TopBar(
    sessionKind: SyncConflictSessionKind,
    fileCount: Int,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AppSpacing.ScreenHorizontalPadding,
                vertical = AppSpacing.MediumSmall,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                stringResource(
                    if (sessionKind == SyncConflictSessionKind.INITIAL_SYNC_PREVIEW) {
                        R.string.sync_conflict_title_initial_preview
                    } else {
                        R.string.sync_conflict_title_standard
                    },
                ),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        Badge(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Text(text = fileCount.toString())
        }
        Spacer(modifier = Modifier.width(AppSpacing.Small))
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
            )
        }
    }
}

@Composable
private fun GlobalActionRow(
    conflictSet: SyncConflictSet,
    onAllChoicesChanged: (SyncConflictResolutionChoice) -> Unit,
    onAcceptSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val supportsSkip =
        conflictSet.source == com.lomo.domain.model.SyncBackendType.S3 ||
            conflictSet.source == com.lomo.domain.model.SyncBackendType.WEBDAV
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        if (conflictSet.sessionKind == SyncConflictSessionKind.INITIAL_SYNC_PREVIEW) {
            FilledTonalButton(
                onClick = onAcceptSuggestions,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.sync_conflict_choice_accept_suggestions))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            OutlinedButton(
                onClick = { onAllChoicesChanged(SyncConflictResolutionChoice.KEEP_LOCAL) },
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.sync_conflict_choice_keep_all_local))
            }
            OutlinedButton(
                onClick = { onAllChoicesChanged(SyncConflictResolutionChoice.KEEP_REMOTE) },
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.sync_conflict_choice_keep_all_remote))
            }
            if (supportsSkip) {
                OutlinedButton(
                    onClick = { onAllChoicesChanged(SyncConflictResolutionChoice.SKIP_FOR_NOW) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.sync_conflict_choice_skip_all))
                }
            }
        }
    }
}

@Composable
private fun ConflictFileCard(
    file: SyncConflictFile,
    choice: SyncConflictResolutionChoice?,
    supportsSkip: Boolean,
    isExpanded: Boolean,
    onChoiceChanged: (SyncConflictResolutionChoice) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    val mergedText =
        remember(file.localContent, file.remoteContent, file.isBinary) {
            if (file.isBinary) {
                null
            } else {
                SyncConflictTextMerge.merge(file.localContent, file.remoteContent)
            }
        }
    val mergeAvailable = mergedText != null && mergedText != file.localContent && mergedText != file.remoteContent
    Card(
        shape = AppShapes.Medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(AppSpacing.CardPadding)) {
            ConflictFileHeader(
                file = file,
                choice = choice,
                mergeAvailable = mergeAvailable,
                supportsSkip = supportsSkip,
                isExpanded = isExpanded,
                onChoiceChanged = onChoiceChanged,
                onToggleExpanded = onToggleExpanded,
            )
            ConflictDiffSection(
                file = file,
                isExpanded = isExpanded,
            )
        }
    }
}

@Composable
private fun ConflictFileHeader(
    file: SyncConflictFile,
    choice: SyncConflictResolutionChoice?,
    mergeAvailable: Boolean,
    supportsSkip: Boolean,
    isExpanded: Boolean,
    onChoiceChanged: (SyncConflictResolutionChoice) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!file.isBinary) {
            Icon(
                imageVector =
                    if (isExpanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier =
                    Modifier
                        .size(24.dp)
                        .clickable { onToggleExpanded() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(AppSpacing.ExtraSmall))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.relativePath.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.isBinary) {
                Text(
                    text = "(binary)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ChoiceToggle(
            choice = choice,
                mergeAvailable = mergeAvailable,
                supportsSkip = supportsSkip,
                onChoiceChanged = onChoiceChanged,
            )
    }
}

@Composable
private fun ConflictDiffSection(
    file: SyncConflictFile,
    isExpanded: Boolean,
) {
    if (file.isBinary) return

    AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        val hunks = remember(file.localContent, file.remoteContent) {
            SimpleLineDiff.diff(
                file.localContent ?: "",
                file.remoteContent ?: "",
            )
        }
        if (hunks.isNotEmpty()) {
            DiffViewer(
                hunks = hunks.toImmutableList(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.Small),
            )
        } else {
            Text(
                text = "No text differences found.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = AppSpacing.Small),
            )
        }
    }
}

@Composable
private fun ChoiceToggle(
    choice: SyncConflictResolutionChoice?,
    mergeAvailable: Boolean,
    supportsSkip: Boolean,
    onChoiceChanged: (SyncConflictResolutionChoice) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val localSelected = choice == SyncConflictResolutionChoice.KEEP_LOCAL
        val remoteSelected = choice == SyncConflictResolutionChoice.KEEP_REMOTE
        val mergeSelected = choice == SyncConflictResolutionChoice.MERGE_TEXT
        val skipSelected = choice == SyncConflictResolutionChoice.SKIP_FOR_NOW

        OutlinedButton(
            onClick = { onChoiceChanged(SyncConflictResolutionChoice.KEEP_LOCAL) },
            shape = AppShapes.Small,
            colors = if (localSelected) {
                androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
            },
        ) {
            Text(
                text = "Local",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        OutlinedButton(
            onClick = { onChoiceChanged(SyncConflictResolutionChoice.KEEP_REMOTE) },
            shape = AppShapes.Small,
            colors = if (remoteSelected) {
                androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
            },
        ) {
            Text(
                text = "Remote",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (mergeAvailable) {
            OutlinedButton(
                onClick = { onChoiceChanged(SyncConflictResolutionChoice.MERGE_TEXT) },
                shape = AppShapes.Small,
                colors = if (mergeSelected) {
                    androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                },
            ) {
                Text(
                    text = stringResource(R.string.sync_conflict_choice_merge),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (supportsSkip) {
            OutlinedButton(
                onClick = { onChoiceChanged(SyncConflictResolutionChoice.SKIP_FOR_NOW) },
                shape = AppShapes.Small,
                colors = if (skipSelected) {
                    androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                },
            ) {
                Text(
                    text = stringResource(R.string.sync_conflict_choice_skip),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun BottomSection(
    allFilesChosen: Boolean,
    onApply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.ScreenHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FilledTonalButton(
            onClick = onApply,
            enabled = allFilesChosen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Apply")
        }
        Spacer(modifier = Modifier.height(AppSpacing.ExtraSmall))
        Text(
            text = "Overwritten files will be backed up before changes are applied.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(AppSpacing.Small))
    }
}

@Composable
private fun ResolvingOverlay() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ExpressiveContainedLoadingIndicator()
                Spacer(modifier = Modifier.height(AppSpacing.Medium))
                Text(
                    text = "Resolving conflicts...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
