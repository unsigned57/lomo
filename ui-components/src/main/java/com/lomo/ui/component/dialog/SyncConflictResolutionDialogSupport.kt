package com.lomo.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.supportsDeferredConflictResolution
import com.lomo.ui.R
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

@Composable
internal fun ConflictSectionHeader(
    text: String,
    count: Int,
) {
    Row(
        modifier = Modifier.padding(top = AppSpacing.Small, bottom = AppSpacing.ExtraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = AppShapes.Small,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = 2.dp),
            )
        }
    }
}

@Composable
internal fun MergePreview(
    mergedText: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, AppShapes.Medium)
            .padding(AppSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
    ) {
        Text(
            text = stringResource(R.string.sync_conflict_merge_preview),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            text = mergedText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun ChoicePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedColor: Color,
    selectedContentColor: Color,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (selected) selectedColor else Color.Transparent
    val contentColor = if (selected) selectedContentColor else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .clip(AppShapes.Medium)
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun choiceLabel(
    choice: SyncConflictResolutionChoice,
): String =
    when (choice) {
        SyncConflictResolutionChoice.KEEP_LOCAL -> stringResource(R.string.sync_conflict_choice_local_short)
        SyncConflictResolutionChoice.KEEP_REMOTE -> stringResource(R.string.sync_conflict_choice_remote_short)
        SyncConflictResolutionChoice.MERGE_TEXT -> stringResource(R.string.sync_conflict_choice_merge)
        SyncConflictResolutionChoice.SKIP_FOR_NOW -> stringResource(R.string.sync_conflict_choice_skip)
    }

internal fun SyncBackendType.supportsDeferredConflictResolutionUi(): Boolean =
    supportsDeferredConflictResolution()
