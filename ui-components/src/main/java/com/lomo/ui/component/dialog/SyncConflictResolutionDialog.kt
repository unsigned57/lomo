package com.lomo.ui.component.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SimpleLineDiff
import com.lomo.ui.component.diff.DiffViewer
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

@Composable
fun SyncConflictResolutionDialog(
    conflictSet: SyncConflictSet,
    perFileChoices: Map<String, SyncConflictResolutionChoice>,
    expandedFilePath: String?,
    isResolving: Boolean,
    onFileChoiceChanged: (path: String, choice: SyncConflictResolutionChoice) -> Unit,
    onAllChoicesChanged: (choice: SyncConflictResolutionChoice) -> Unit,
    onToggleExpanded: (path: String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val allFilesChosen = conflictSet.files.all { file ->
        perFileChoices.containsKey(file.relativePath)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top bar
                    TopBar(
                        fileCount = conflictSet.files.size,
                        onDismiss = onDismiss,
                    )

                    // Global action row
                    GlobalActionRow(
                        onAllChoicesChanged = onAllChoicesChanged,
                        modifier = Modifier.padding(
                            horizontal = AppSpacing.ScreenHorizontalPadding,
                        ),
                    )

                    Spacer(modifier = Modifier.height(AppSpacing.Small))

                    // File list
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.ScreenHorizontalPadding),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                    ) {
                        items(
                            items = conflictSet.files,
                            key = { it.relativePath },
                        ) { file ->
                            ConflictFileCard(
                                file = file,
                                choice = perFileChoices[file.relativePath],
                                isExpanded = expandedFilePath == file.relativePath,
                                onChoiceChanged = { choice ->
                                    onFileChoiceChanged(file.relativePath, choice)
                                },
                                onToggleExpanded = {
                                    onToggleExpanded(file.relativePath)
                                },
                            )
                        }
                    }

                    // Bottom section
                    BottomSection(
                        allFilesChosen = allFilesChosen,
                        onApply = onApply,
                    )
                }

                // Loading overlay
                if (isResolving) {
                    ResolvingOverlay()
                }
            }
        }
    }
}

@Composable
private fun TopBar(
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
            text = "Resolve Sync Conflicts",
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
    onAllChoicesChanged: (SyncConflictResolutionChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        OutlinedButton(
            onClick = { onAllChoicesChanged(SyncConflictResolutionChoice.KEEP_LOCAL) },
            modifier = Modifier.weight(1f),
        ) {
            Text(text = "Keep All Local")
        }
        OutlinedButton(
            onClick = { onAllChoicesChanged(SyncConflictResolutionChoice.KEEP_REMOTE) },
            modifier = Modifier.weight(1f),
        ) {
            Text(text = "Keep All Remote")
        }
    }
}

@Composable
private fun ConflictFileCard(
    file: SyncConflictFile,
    choice: SyncConflictResolutionChoice?,
    isExpanded: Boolean,
    onChoiceChanged: (SyncConflictResolutionChoice) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    Card(
        shape = AppShapes.Medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(AppSpacing.CardPadding)) {
            // Header row: filename + choice toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Expand/collapse for text files
                if (!file.isBinary) {
                    Icon(
                        imageVector = if (isExpanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier
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

                // Local / Remote toggle buttons
                ChoiceToggle(
                    choice = choice,
                    onChoiceChanged = onChoiceChanged,
                )
            }

            // Diff section for text files
            if (!file.isBinary) {
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
                            hunks = hunks,
                            modifier = Modifier
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
        }
    }
}

@Composable
private fun ChoiceToggle(
    choice: SyncConflictResolutionChoice?,
    onChoiceChanged: (SyncConflictResolutionChoice) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val localSelected = choice == SyncConflictResolutionChoice.KEEP_LOCAL
        val remoteSelected = choice == SyncConflictResolutionChoice.KEEP_REMOTE

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
