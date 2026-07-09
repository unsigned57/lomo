package com.lomo.ui.component.dialog

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.*
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableMap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopBar(
    source: SyncBackendType,
    isInitialImportPreview: Boolean,
    onDismiss: () -> Unit,
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = stringResource(dialogTitle(source, isInitialImportPreview)),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        },
        navigationIcon = {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.sync_conflict_close),
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
internal fun GlobalActionRow(
    conflictSet: SyncConflictSet,
    canAutoResolveSafe: Boolean,
    perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    isInitialImportPreview: Boolean,
    fileCount: Int,
    onAllChoicesChanged: (SyncConflictResolutionChoice) -> Unit,
    onAcceptSuggestions: () -> Unit,
    onAutoResolveSafeConflicts: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DialogSuggestionActions(
            canAutoResolveSafe = canAutoResolveSafe,
            isInitialImportPreview = isInitialImportPreview,
            onAcceptSuggestions = onAcceptSuggestions,
            onAutoResolveSafe = onAutoResolveSafeConflicts,
        )
        ConflictGlobalChoiceRow(
            source = conflictSet.source,
            globalChoice = resolveGlobalChoice(perFileChoices, fileCount),
            onAllChoicesChanged = onAllChoicesChanged,
        )
    }
}

@Composable
internal fun ReviewGlobalActionRow(
    source: SyncBackendType,
    canAutoResolveSafe: Boolean,
    perItemChoices: ImmutableMap<String, SyncReviewResolutionChoice>,
    isInitialImportPreview: Boolean,
    itemCount: Int,
    onAllItemChoicesChanged: (SyncReviewResolutionChoice) -> Unit,
    onAcceptSuggestions: () -> Unit,
    onAutoResolveSafeReviews: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DialogSuggestionActions(
            canAutoResolveSafe = canAutoResolveSafe,
            isInitialImportPreview = isInitialImportPreview,
            onAcceptSuggestions = onAcceptSuggestions,
            onAutoResolveSafe = onAutoResolveSafeReviews,
        )
        ReviewGlobalChoiceRow(
            source = source,
            globalChoice = resolveReviewGlobalChoice(perItemChoices, itemCount),
            onAllItemChoicesChanged = onAllItemChoicesChanged,
        )
    }
}

@Composable
internal fun BottomSection(
    allFilesChosen: Boolean,
    onApply: () -> Unit,
) {
    Surface(
        modifier = Modifier.wrapContentHeight(),
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = SyncConflictDialogTokens.SheetShape,
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
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = AppShapes.Large,
            ) {
                Text(
                    text = stringResource(Res.string.sync_conflict_apply_resolution),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
internal fun ResolvingOverlay() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = SyncConflictDialogTokens.scrimColor(MaterialTheme.colorScheme),
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
                        text = stringResource(Res.string.sync_conflict_resolving),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogSuggestionActions(
    canAutoResolveSafe: Boolean,
    isInitialImportPreview: Boolean,
    onAcceptSuggestions: () -> Unit,
    onAutoResolveSafe: () -> Unit,
) {
    if (!isInitialImportPreview && !canAutoResolveSafe) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AppSpacing.ScreenHorizontalPadding,
                vertical = AppSpacing.ExtraSmall,
            )
            .padding(bottom = AppSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        if (isInitialImportPreview) {
            SuggestionButton(
                text = stringResource(Res.string.sync_conflict_choice_accept_suggestions),
                onClick = onAcceptSuggestions,
                primary = true,
            )
        }
        if (canAutoResolveSafe) {
            SuggestionButton(
                text = stringResource(Res.string.sync_conflict_choice_auto_resolve_safe),
                onClick = onAutoResolveSafe,
                primary = false,
            )
        }
    }
}

@Composable
private fun SuggestionButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        },
        shape = AppShapes.Large,
    ) {
        Icon(
            imageVector = if (primary) Icons.Default.AutoAwesome else Icons.Default.DoneAll,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(AppSpacing.ExtraSmall))
        Text(text = text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
    }
}

private fun dialogTitle(
    source: SyncBackendType,
    isInitialImportPreview: Boolean,
): StringResource =
    when {
        source == SyncBackendType.INBOX -> Res.string.sync_conflict_title_inbox_review
        isInitialImportPreview -> Res.string.sync_conflict_title_initial_preview
        else -> Res.string.sync_conflict_title_standard
    }
