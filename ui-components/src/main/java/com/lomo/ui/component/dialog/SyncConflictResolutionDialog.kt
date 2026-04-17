package com.lomo.ui.component.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lomo.domain.model.SimpleLineDiff
import com.lomo.domain.model.SyncConflictAutoResolutionAdvisor
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSessionKind
import com.lomo.domain.model.SyncConflictSet
import com.lomo.ui.R
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.diff.DiffViewer
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
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
                        sessionKind = conflictSet.sessionKind,
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

@Composable
private fun ConflictFileList(
    source: SyncBackendType,
    files: ImmutableList<SyncConflictFile>,
    safeChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    suggestedChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    expandedFilePath: String?,
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
        contentPadding = PaddingValues(
            start = AppSpacing.ScreenHorizontalPadding,
            end = AppSpacing.ScreenHorizontalPadding,
            top = AppSpacing.Small,
            bottom = AppSpacing.ExtraLarge + AppSpacing.ExtraLarge + AppSpacing.ExtraLarge,
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
    ) {
        if (autoResolvableFiles.isNotEmpty()) {
            item(key = "auto-resolvable-header") {
                ConflictSectionHeader(
                    text = stringResource(R.string.sync_conflict_section_auto),
                    count = autoResolvableFiles.size,
                )
            }
            items(
                items = autoResolvableFiles,
                key = { it.relativePath },
            ) { file ->
                ConflictFileCard(
                    file = file,
                    choice = perFileChoices[file.relativePath],
                    suggestedChoice = suggestedChoices[file.relativePath],
                    supportsSkip = supportsSkip,
                    isExpanded = expandedFilePath == file.relativePath,
                    onChoiceChanged = { choice ->
                        onFileChoiceChanged(file.relativePath, choice)
                    },
                    onToggleExpanded = { onToggleExpanded(file.relativePath) },
                )
            }
        }
        if (manualFiles.isNotEmpty()) {
            item(key = "manual-header") {
                ConflictSectionHeader(
                    text = stringResource(R.string.sync_conflict_section_manual),
                    count = manualFiles.size,
                )
            }
            items(
                items = manualFiles,
                key = { it.relativePath },
            ) { file ->
                ConflictFileCard(
                    file = file,
                    choice = perFileChoices[file.relativePath],
                    suggestedChoice = null,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    sessionKind: SyncConflictSessionKind,
    onDismiss: () -> Unit,
) {
    CenterAlignedTopAppBar(
        title = {
            val titleText = stringResource(
                if (sessionKind == SyncConflictSessionKind.INITIAL_SYNC_PREVIEW) {
                    R.string.sync_conflict_title_initial_preview
                } else {
                    R.string.sync_conflict_title_standard
                },
            )
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        },
        navigationIcon = {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.sync_conflict_close),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

@Composable
private fun GlobalActionRow(
    conflictSet: SyncConflictSet,
    canAutoResolveSafe: Boolean,
    perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    fileCount: Int,
    onAllChoicesChanged: (SyncConflictResolutionChoice) -> Unit,
    onAcceptSuggestions: () -> Unit,
    onAutoResolveSafeConflicts: () -> Unit,
) {
    val supportsSkip = conflictSet.source.supportsDeferredConflictResolutionUi()
    val globalChoice = resolveGlobalChoice(perFileChoices, fileCount)

    Column(modifier = Modifier.fillMaxWidth()) {
        if (conflictSet.sessionKind == SyncConflictSessionKind.INITIAL_SYNC_PREVIEW || canAutoResolveSafe) {
            Column(
                modifier = Modifier.padding(
                    horizontal = AppSpacing.ScreenHorizontalPadding,
                    vertical = AppSpacing.ExtraSmall,
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            ) {
                if (conflictSet.sessionKind == SyncConflictSessionKind.INITIAL_SYNC_PREVIEW) {
                    Button(
                        onClick = onAcceptSuggestions,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        shape = AppShapes.Large,
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.ExtraSmall))
                        Text(
                            text = stringResource(R.string.sync_conflict_choice_accept_suggestions),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }
                if (canAutoResolveSafe) {
                    Button(
                        onClick = onAutoResolveSafeConflicts,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        shape = AppShapes.Large,
                    ) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.ExtraSmall))
                        Text(text = stringResource(R.string.sync_conflict_choice_auto_resolve_safe))
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.Small))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.ScreenHorizontalPadding, vertical = AppSpacing.Small)
                .clip(AppShapes.Large)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChoicePill(
                text = stringResource(R.string.sync_conflict_choice_local_short),
                selected = globalChoice == SyncConflictResolutionChoice.KEEP_LOCAL,
                onClick = { onAllChoicesChanged(SyncConflictResolutionChoice.KEEP_LOCAL) },
                selectedColor = MaterialTheme.colorScheme.primaryContainer,
                selectedContentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            ChoicePill(
                text = stringResource(R.string.sync_conflict_choice_remote_short),
                selected = globalChoice == SyncConflictResolutionChoice.KEEP_REMOTE,
                onClick = { onAllChoicesChanged(SyncConflictResolutionChoice.KEEP_REMOTE) },
                selectedColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedContentColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            if (supportsSkip) {
                ChoicePill(
                    text = stringResource(R.string.sync_conflict_choice_skip),
                    selected = globalChoice == SyncConflictResolutionChoice.SKIP_FOR_NOW,
                    onClick = { onAllChoicesChanged(SyncConflictResolutionChoice.SKIP_FOR_NOW) },
                    selectedColor = MaterialTheme.colorScheme.errorContainer,
                    selectedContentColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun resolveGlobalChoice(
    perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    fileCount: Int,
): SyncConflictResolutionChoice? {
    if (perFileChoices.size != fileCount || fileCount == 0) return null
    val first = perFileChoices.values.firstOrNull() ?: return null
    return if (perFileChoices.values.all { it == first }) first else null
}

@Composable
private fun ConflictFileCard(
    file: SyncConflictFile,
    choice: SyncConflictResolutionChoice?,
    suggestedChoice: SyncConflictResolutionChoice?,
    supportsSkip: Boolean,
    isExpanded: Boolean,
    onChoiceChanged: (SyncConflictResolutionChoice) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    val mergedText =
        remember(file.localContent, file.remoteContent, file.isBinary) {
            SyncConflictAutoResolutionAdvisor.mergedText(file)
        }
    val mergeAvailable = mergedText != null && mergedText != file.localContent && mergedText != file.remoteContent

    val cardColor by animateColorAsState(
        targetValue = if (choice != null) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "cardColor",
    )

    Card(
        shape = AppShapes.ExtraLarge,
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.animateContentSize(),
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Medium)) {
            ConflictFileHeader(
                file = file,
                isExpanded = isExpanded,
                suggestedChoice = suggestedChoice,
                onToggleExpanded = onToggleExpanded,
            )
            Spacer(modifier = Modifier.height(AppSpacing.Medium))
            CustomChoiceToggle(
                choice = choice,
                mergeAvailable = mergeAvailable,
                supportsSkip = supportsSkip,
                onChoiceChanged = onChoiceChanged,
            )
            ConflictDiffSection(
                file = file,
                isExpanded = isExpanded,
                mergedText = mergedText,
                mergeAvailable = mergeAvailable,
            )
        }
    }
}

@Composable
private fun ConflictFileHeader(
    file: SyncConflictFile,
    isExpanded: Boolean,
    suggestedChoice: SyncConflictResolutionChoice?,
    onToggleExpanded: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.Small)
            .clickable(enabled = !file.isBinary) { onToggleExpanded() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!file.isBinary) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) {
                        stringResource(R.string.sync_conflict_collapse)
                    } else {
                        stringResource(R.string.sync_conflict_expand)
                    },
                    modifier = Modifier.padding(4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.MediumSmall))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.relativePath.substringAfterLast('/'),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
            ) {
                val subt =
                    if (file.isBinary) {
                        stringResource(R.string.sync_conflict_binary_file)
                    } else {
                        file.relativePath.substringBeforeLast('/', missingDelimiterValue = "")
                    }
                Text(
                    text = subt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (suggestedChoice != null) {
                    Surface(
                        shape = AppShapes.Small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = choiceLabel(suggestedChoice),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictDiffSection(
    file: SyncConflictFile,
    isExpanded: Boolean,
    mergedText: String?,
    mergeAvailable: Boolean,
) {
    if (file.isBinary) return

    AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        val hunks = remember(file.localContent, file.remoteContent) {
            SimpleLineDiff.diff(file.localContent ?: "", file.remoteContent ?: "")
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.Medium)
                    .background(MaterialTheme.colorScheme.background)
                    .horizontalScroll(rememberScrollState()),
            ) {
                if (hunks.isNotEmpty()) {
                    DiffViewer(
                        hunks = hunks.toImmutableList(),
                        modifier = Modifier.padding(AppSpacing.ExtraSmall),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.sync_conflict_no_text_diffs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(AppSpacing.Medium),
                    )
                }
            }
            if (mergeAvailable && mergedText != null) {
                MergePreview(mergedText)
            }
        }
    }
}

@Composable
private fun CustomChoiceToggle(
    choice: SyncConflictResolutionChoice?,
    mergeAvailable: Boolean,
    supportsSkip: Boolean,
    onChoiceChanged: (SyncConflictResolutionChoice) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.Large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChoicePill(
            text = stringResource(R.string.sync_conflict_choice_local_short),
            selected = choice == SyncConflictResolutionChoice.KEEP_LOCAL,
            onClick = { onChoiceChanged(SyncConflictResolutionChoice.KEEP_LOCAL) },
            selectedColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        ChoicePill(
            text = stringResource(R.string.sync_conflict_choice_remote_short),
            selected = choice == SyncConflictResolutionChoice.KEEP_REMOTE,
            onClick = { onChoiceChanged(SyncConflictResolutionChoice.KEEP_REMOTE) },
            selectedColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        if (mergeAvailable) {
            ChoicePill(
                text = stringResource(R.string.sync_conflict_choice_merge),
                selected = choice == SyncConflictResolutionChoice.MERGE_TEXT,
                onClick = { onChoiceChanged(SyncConflictResolutionChoice.MERGE_TEXT) },
                selectedColor = MaterialTheme.colorScheme.tertiaryContainer,
                selectedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
        }
        if (supportsSkip) {
            ChoicePill(
                text = stringResource(R.string.sync_conflict_choice_skip),
                selected = choice == SyncConflictResolutionChoice.SKIP_FOR_NOW,
                onClick = { onChoiceChanged(SyncConflictResolutionChoice.SKIP_FOR_NOW) },
                selectedColor = MaterialTheme.colorScheme.errorContainer,
                selectedContentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BottomSection(
    allFilesChosen: Boolean,
    onApply: () -> Unit,
) {
    Surface(
        modifier = Modifier.wrapContentHeight(),
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = AppSpacing.ScreenHorizontalPadding)
                .padding(top = AppSpacing.Medium, bottom = AppSpacing.Large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onApply,
                enabled = allFilesChosen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = AppShapes.Large,
            ) {
                Text(
                    text = stringResource(R.string.sync_conflict_apply_resolution),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
private fun ResolvingOverlay() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Card(
                shape = AppShapes.ExtraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.ExtraLarge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ExpressiveContainedLoadingIndicator()
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    Text(
                        text = stringResource(R.string.sync_conflict_resolving),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
