package com.lomo.app.feature.memo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.MemoVersion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val VERSION_COMMIT_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatCommitTime(commitTimeMillis: Long): String =
    Instant
        .ofEpochMilli(commitTimeMillis)
        .atZone(ZoneId.systemDefault())
        .format(VERSION_COMMIT_TIME_FORMATTER)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoVersionHistorySheet(
    versions: List<MemoVersion>,
    isLoading: Boolean,
    onRestore: (MemoVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.memo_version_history),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (!isLoading && versions.isNotEmpty()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = versions.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.memo_version_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (versions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.memo_version_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(versions, key = { _, version -> version.commitHash }) { _, version ->
                        VersionItem(
                            version = version,
                            formattedTime = formatCommitTime(version.commitTime),
                            onRestore = { onRestore(version) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionItem(
    version: MemoVersion,
    formattedTime: String,
    onRestore: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = version.commitMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (version.isCurrent) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = {
                            Text(
                                text = stringResource(R.string.memo_version_current),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        colors =
                            AssistChipDefaults.assistChipColors(
                                disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                disabledLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                    )
                } else {
                    TextButton(
                        onClick = onRestore,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.memo_version_restore),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = version.memoContent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
