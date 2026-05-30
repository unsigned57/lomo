package com.lomo.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.ui.R
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableMap

@Composable
internal fun ConflictGlobalChoiceRow(
    source: SyncBackendType,
    globalChoice: SyncConflictResolutionChoice?,
    onAllChoicesChanged: (SyncConflictResolutionChoice) -> Unit,
) {
    ChoiceRow {
        ConflictChoicePill(
            source = source,
            choice = SyncConflictResolutionChoice.KEEP_LOCAL,
            selected = globalChoice == SyncConflictResolutionChoice.KEEP_LOCAL,
            onChoiceChanged = onAllChoicesChanged,
        )
        ConflictChoicePill(
            source = source,
            choice = SyncConflictResolutionChoice.KEEP_REMOTE,
            selected = globalChoice == SyncConflictResolutionChoice.KEEP_REMOTE,
            onChoiceChanged = onAllChoicesChanged,
        )
        if (source.supportsDeferredConflictResolutionUi()) {
            ConflictChoicePill(
                source = source,
                choice = SyncConflictResolutionChoice.SKIP_FOR_NOW,
                selected = globalChoice == SyncConflictResolutionChoice.SKIP_FOR_NOW,
                onChoiceChanged = onAllChoicesChanged,
            )
        }
    }
}

@Composable
internal fun ReviewGlobalChoiceRow(
    source: SyncBackendType,
    globalChoice: SyncReviewResolutionChoice?,
    onAllItemChoicesChanged: (SyncReviewResolutionChoice) -> Unit,
) {
    ChoiceRow {
        ReviewChoicePill(
            source = source,
            choice = SyncReviewResolutionChoice.KEEP_LOCAL,
            selected = globalChoice == SyncReviewResolutionChoice.KEEP_LOCAL,
            onChoiceChanged = onAllItemChoicesChanged,
        )
        ReviewChoicePill(
            source = source,
            choice = SyncReviewResolutionChoice.KEEP_INCOMING,
            selected = globalChoice == SyncReviewResolutionChoice.KEEP_INCOMING,
            onChoiceChanged = onAllItemChoicesChanged,
        )
        if (source.supportsDeferredReviewResolutionUi()) {
            ReviewChoicePill(
                source = source,
                choice = SyncReviewResolutionChoice.SKIP_FOR_NOW,
                selected = globalChoice == SyncReviewResolutionChoice.SKIP_FOR_NOW,
                onChoiceChanged = onAllItemChoicesChanged,
            )
        }
    }
}

@Composable
internal fun CustomChoiceToggle(
    source: SyncBackendType,
    choice: SyncConflictResolutionChoice?,
    mergeAvailable: Boolean,
    supportsSkip: Boolean,
    onChoiceChanged: (SyncConflictResolutionChoice) -> Unit,
) {
    ChoiceRow(useScreenPadding = false) {
        ConflictChoicePill(source, SyncConflictResolutionChoice.KEEP_LOCAL, choice, onChoiceChanged)
        ConflictChoicePill(source, SyncConflictResolutionChoice.KEEP_REMOTE, choice, onChoiceChanged)
        if (mergeAvailable) {
            ConflictChoicePill(source, SyncConflictResolutionChoice.MERGE_TEXT, choice, onChoiceChanged)
        }
        if (supportsSkip) {
            ConflictChoicePill(source, SyncConflictResolutionChoice.SKIP_FOR_NOW, choice, onChoiceChanged)
        }
    }
}

@Composable
internal fun ReviewChoiceToggle(
    source: SyncBackendType,
    choice: SyncReviewResolutionChoice?,
    mergeAvailable: Boolean,
    supportsSkip: Boolean,
    onChoiceChanged: (SyncReviewResolutionChoice) -> Unit,
) {
    ChoiceRow(useScreenPadding = false) {
        ReviewChoicePill(source, SyncReviewResolutionChoice.KEEP_LOCAL, choice, onChoiceChanged)
        ReviewChoicePill(source, SyncReviewResolutionChoice.KEEP_INCOMING, choice, onChoiceChanged)
        if (mergeAvailable) {
            ReviewChoicePill(source, SyncReviewResolutionChoice.MERGE_TEXT, choice, onChoiceChanged)
        }
        if (supportsSkip) {
            ReviewChoicePill(source, SyncReviewResolutionChoice.SKIP_FOR_NOW, choice, onChoiceChanged)
        }
    }
}

internal fun resolveGlobalChoice(
    perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
    fileCount: Int,
): SyncConflictResolutionChoice? {
    if (perFileChoices.size != fileCount || fileCount == 0) return null
    val first = perFileChoices.values.firstOrNull() ?: return null
    return if (perFileChoices.values.all { it == first }) first else null
}

internal fun resolveReviewGlobalChoice(
    perItemChoices: ImmutableMap<String, SyncReviewResolutionChoice>,
    itemCount: Int,
): SyncReviewResolutionChoice? {
    if (perItemChoices.size != itemCount || itemCount == 0) return null
    val first = perItemChoices.values.firstOrNull() ?: return null
    return if (perItemChoices.values.all { it == first }) first else null
}

@Composable
private fun ChoiceRow(
    useScreenPadding: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (useScreenPadding) {
                    Modifier.padding(
                        horizontal = AppSpacing.ScreenHorizontalPadding,
                        vertical = AppSpacing.Small,
                    )
                } else {
                    Modifier
                },
            )
            .clip(AppShapes.Large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun RowScope.ConflictChoicePill(
    source: SyncBackendType,
    choice: SyncConflictResolutionChoice,
    selectedChoice: SyncConflictResolutionChoice?,
    onChoiceChanged: (SyncConflictResolutionChoice) -> Unit,
) {
    ConflictChoicePill(
        source = source,
        choice = choice,
        selected = selectedChoice == choice,
        onChoiceChanged = onChoiceChanged,
    )
}

@Composable
private fun RowScope.ConflictChoicePill(
    source: SyncBackendType,
    choice: SyncConflictResolutionChoice,
    selected: Boolean,
    onChoiceChanged: (SyncConflictResolutionChoice) -> Unit,
) {
    ChoicePill(
        text = choiceLabel(choice, source),
        selected = selected,
        onClick = { onChoiceChanged(choice) },
        selectedColor = choice.selectedContainerColor(),
        selectedContentColor = choice.selectedContentColor(),
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun RowScope.ReviewChoicePill(
    source: SyncBackendType,
    choice: SyncReviewResolutionChoice,
    selectedChoice: SyncReviewResolutionChoice?,
    onChoiceChanged: (SyncReviewResolutionChoice) -> Unit,
) {
    ReviewChoicePill(
        source = source,
        choice = choice,
        selected = selectedChoice == choice,
        onChoiceChanged = onChoiceChanged,
    )
}

@Composable
private fun RowScope.ReviewChoicePill(
    source: SyncBackendType,
    choice: SyncReviewResolutionChoice,
    selected: Boolean,
    onChoiceChanged: (SyncReviewResolutionChoice) -> Unit,
) {
    ChoicePill(
        text = reviewChoiceLabel(choice, source),
        selected = selected,
        onClick = { onChoiceChanged(choice) },
        selectedColor = choice.selectedContainerColor(),
        selectedContentColor = choice.selectedContentColor(),
        modifier = Modifier.weight(1f),
    )
}
