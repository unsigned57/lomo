package com.lomo.ui.component.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.SimpleLineDiff
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.*
import com.lomo.ui.component.diff.DiffViewer
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun ConflictFileHeader(
    source: SyncBackendType,
    file: SyncConflictFile,
    isExpanded: Boolean,
    suggestedChoice: SyncConflictResolutionChoice?,
    onToggleExpanded: () -> Unit,
) {
    ConflictReviewHeader(
        relativePath = file.relativePath,
        isBinary = file.isBinary,
        isExpanded = isExpanded,
        suggestedLabel = suggestedChoice?.let { choiceLabel(it, source) },
        onToggleExpanded = onToggleExpanded,
    )
}

@Composable
internal fun ReviewFileHeader(
    source: SyncBackendType,
    item: SyncReviewItem,
    isExpanded: Boolean,
    suggestedChoice: SyncReviewResolutionChoice?,
    onToggleExpanded: () -> Unit,
) {
    ConflictReviewHeader(
        relativePath = item.relativePath,
        isBinary = item.isBinary,
        isExpanded = isExpanded,
        suggestedLabel = suggestedChoice?.let { reviewChoiceLabel(it, source) },
        onToggleExpanded = onToggleExpanded,
    )
}

@Composable
internal fun ConflictDiffSection(
    file: SyncConflictFile,
    isExpanded: Boolean,
    mergedText: String?,
    mergeAvailable: Boolean,
) {
    if (file.isBinary) return
    TextDiffSection(
        isExpanded = isExpanded,
        localContent = file.localContent,
        incomingContent = file.remoteContent,
        mergedText = mergedText,
        mergeAvailable = mergeAvailable,
    )
}

@Composable
internal fun ReviewDiffSection(
    item: SyncReviewItem,
    isExpanded: Boolean,
    mergedText: String?,
    mergeAvailable: Boolean,
) {
    if (item.isBinary) return
    TextDiffSection(
        isExpanded = isExpanded,
        localContent = item.localContent,
        incomingContent = item.incomingContent,
        mergedText = mergedText,
        mergeAvailable = mergeAvailable,
    )
}

@Composable
private fun ConflictReviewHeader(
    relativePath: String,
    isBinary: Boolean,
    isExpanded: Boolean,
    suggestedLabel: String?,
    onToggleExpanded: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.Small)
            .clickable(enabled = !isBinary) { onToggleExpanded() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderExpandIcon(isBinary = isBinary, isExpanded = isExpanded)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = relativePath.substringAfterLast('/'),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HeaderSubtitleRow(
                relativePath = relativePath,
                isBinary = isBinary,
                suggestedLabel = suggestedLabel,
            )
        }
    }
}

@Composable
private fun HeaderExpandIcon(
    isBinary: Boolean,
    isExpanded: Boolean,
) {
    if (isBinary) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) {
                    stringResource(Res.string.sync_conflict_collapse)
                } else {
                    stringResource(Res.string.sync_conflict_expand)
                },
                modifier = Modifier.padding(4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.MediumSmall))
    }
}

@Composable
private fun HeaderSubtitleRow(
    relativePath: String,
    isBinary: Boolean,
    suggestedLabel: String?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
    ) {
        Text(
            text = if (isBinary) {
                stringResource(Res.string.sync_conflict_binary_file)
            } else {
                relativePath.substringBeforeLast('/', missingDelimiterValue = "")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (suggestedLabel != null) {
            SuggestedChoiceLabel(label = suggestedLabel)
        }
    }
}

@Composable
private fun SuggestedChoiceLabel(label: String) {
    Surface(
        shape = AppShapes.Small,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = 4.dp),
        )
    }
}

@Composable
private fun TextDiffSection(
    isExpanded: Boolean,
    localContent: String?,
    incomingContent: String?,
    mergedText: String?,
    mergeAvailable: Boolean,
) {
    AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
        val diffResult = remember(localContent, incomingContent) {
            SimpleLineDiff.diffResult(localContent ?: "", incomingContent ?: "")
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            TextDiffResultBox(result = diffResult)
            if (mergeAvailable && mergedText != null) {
                MergePreview(mergedText)
            }
        }
    }
}

@Composable
private fun TextDiffResultBox(result: SimpleLineDiff.DiffResult) {
    val diffPresentation = remember(result) {
        resolveConflictTextDiffPresentation(result)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.Medium)
            .background(MaterialTheme.colorScheme.background)
            .horizontalScroll(rememberScrollState()),
    ) {
        when (diffPresentation) {
            is ConflictTextDiffPresentation.Diff ->
                DiffViewer(
                    hunks = diffPresentation.hunks.toImmutableList(),
                    modifier = Modifier.padding(AppSpacing.ExtraSmall),
                )
            ConflictTextDiffPresentation.NoTextDiffs ->
                DiffPlaceholderText(Res.string.sync_conflict_no_text_diffs)
            ConflictTextDiffPresentation.TooLarge ->
                DiffPlaceholderText(Res.string.sync_conflict_text_diff_too_large)
        }
    }
}

@Composable
private fun DiffPlaceholderText(textRes: StringResource) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(AppSpacing.Medium),
    )
}
