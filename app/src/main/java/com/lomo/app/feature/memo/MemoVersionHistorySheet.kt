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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.R
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.component.markdown.ModernMarkdownRenderPlan
import kotlinx.collections.immutable.ImmutableList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val VERSION_COMMIT_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private const val VERSION_PREVIEW_MAX_VISIBLE_BLOCKS = 3

internal enum class VersionHistoryCardInteraction {
    Static,
    Restore,
}

internal enum class VersionHistoryCardHighlight {
    Standard,
    Current,
}

internal data class VersionHistoryCardPresentation(
    val interaction: VersionHistoryCardInteraction,
    val highlight: VersionHistoryCardHighlight,
    val isBusy: Boolean,
)

private fun formatCommitTime(commitTimeMillis: Long): String =
    Instant
        .ofEpochMilli(commitTimeMillis)
        .atZone(ZoneId.systemDefault())
        .format(VERSION_COMMIT_TIME_FORMATTER)

internal fun resolveVersionHistoryCardPresentation(
    version: MemoRevision,
    isRestoreInProgress: Boolean,
    restoringRevisionId: String?,
): VersionHistoryCardPresentation =
    VersionHistoryCardPresentation(
        interaction =
            if (version.isCurrent || isRestoreInProgress) {
                VersionHistoryCardInteraction.Static
            } else {
                VersionHistoryCardInteraction.Restore
            },
        highlight =
            if (version.isCurrent) {
                VersionHistoryCardHighlight.Current
            } else {
                VersionHistoryCardHighlight.Standard
            },
        isBusy =
            !version.isCurrent &&
                isRestoreInProgress &&
                restoringRevisionId == version.revisionId,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoVersionHistorySheet(
    versions: ImmutableList<MemoVersionHistoryUiModel>,
    isLoading: Boolean,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    isRestoreInProgress: Boolean,
    restoringRevisionId: String?,
    onLoadMore: () -> Unit,
    onRestore: (MemoRevision) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .benchmarkAnchorRoot(BenchmarkAnchorContract.VERSION_HISTORY_ROOT)
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .padding(bottom = 24.dp),
        ) {
            MemoVersionHistoryHeader(
            )
            Spacer(modifier = Modifier.height(12.dp))
            MemoVersionHistoryBody(
                versions = versions,
                isLoading = isLoading,
                canLoadMore = canLoadMore,
                isLoadingMore = isLoadingMore,
                isRestoreInProgress = isRestoreInProgress,
                restoringRevisionId = restoringRevisionId,
                onLoadMore = onLoadMore,
                onRestore = onRestore,
            )
        }
    }
}

@Composable
private fun MemoVersionHistoryHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(10.dp),
            )
        }
        Text(
            text = stringResource(R.string.memo_version_history),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun MemoVersionHistoryBody(
    versions: ImmutableList<MemoVersionHistoryUiModel>,
    isLoading: Boolean,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    isRestoreInProgress: Boolean,
    restoringRevisionId: String?,
    onLoadMore: () -> Unit,
    onRestore: (MemoRevision) -> Unit,
) {
    when {
        isLoading -> {
            MemoVersionHistoryPlaceholder(
                text = stringResource(R.string.memo_version_loading),
                showLoadingIndicator = true,
            )
        }

        versions.isEmpty() -> {
            MemoVersionHistoryPlaceholder(
                text = stringResource(R.string.memo_version_history_empty),
                showLoadingIndicator = false,
            )
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(versions, key = { version -> version.revision.revisionId }) { version ->
                    VersionTimelineItem(
                        version = version.revision,
                        processedContent = version.processedContent,
                        precomputedRenderPlan = version.precomputedRenderPlan,
                        formattedTime = formatCommitTime(version.revision.createdAt),
                        isRestoreInProgress = isRestoreInProgress,
                        restoringRevisionId = restoringRevisionId,
                        onRestore = { onRestore(version.revision) },
                    )
                }
                if (canLoadMore || isLoadingMore) {
                    item(key = "load-more") {
                        LoadMoreHistoryItem(
                            isLoadingMore = isLoadingMore,
                            enabled = !isRestoreInProgress,
                            onLoadMore = onLoadMore,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoVersionHistoryPlaceholder(
    text: String,
    showLoadingIndicator: Boolean,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(132.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (showLoadingIndicator) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ExpressiveContainedLoadingIndicator(modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VersionTimelineItem(
    version: MemoRevision,
    processedContent: String,
    precomputedRenderPlan: ModernMarkdownRenderPlan,
    formattedTime: String,
    isRestoreInProgress: Boolean,
    restoringRevisionId: String?,
    onRestore: () -> Unit,
) {
    val presentation =
        resolveVersionHistoryCardPresentation(
            version = version,
            isRestoreInProgress = isRestoreInProgress,
            restoringRevisionId = restoringRevisionId,
        )
    val containerColor =
        if (presentation.highlight == VersionHistoryCardHighlight.Current) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        }
    val topMetaColor =
        if (presentation.highlight == VersionHistoryCardHighlight.Current) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    val content: @Composable () -> Unit = {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = topMetaColor,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (presentation.isBusy) {
                        VersionRestoringBadge()
                    }
                    if (version.lifecycleState != MemoRevisionLifecycleState.ACTIVE) {
                        VersionLifecycleBadge(lifecycleState = version.lifecycleState)
                    }
                }
            }
            VersionContentPreview(
                content = processedContent,
                precomputedRenderPlan = precomputedRenderPlan,
            )
        }
    }

    when (presentation.interaction) {
        VersionHistoryCardInteraction.Restore -> {
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .benchmarkAnchor(BenchmarkAnchorContract.versionHistoryRestore(version.revisionId)),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = containerColor),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    content()
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .clickable(
                                    interactionSource =
                                        remember {
                                            MutableInteractionSource()
                                        },
                                    indication = null,
                                    onClick = onRestore,
                                ),
                    )
                }
            }
        }

        VersionHistoryCardInteraction.Static -> {
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .benchmarkAnchor(BenchmarkAnchorContract.versionHistoryCard(version.revisionId)),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = containerColor),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun VersionContentPreview(
    content: String,
    precomputedRenderPlan: ModernMarkdownRenderPlan,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MarkdownRenderer(
            content = content,
            modifier = Modifier.fillMaxWidth(),
            maxVisibleBlocks = VERSION_PREVIEW_MAX_VISIBLE_BLOCKS,
            precomputedRenderPlan = precomputedRenderPlan,
        )
    }
}

@Composable
private fun VersionRestoringBadge() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveLoadingIndicator(
                modifier = Modifier.size(14.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.memo_version_restoring),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun VersionLifecycleBadge(lifecycleState: MemoRevisionLifecycleState) {
    Surface(
        shape = CircleShape,
        color =
            when (lifecycleState) {
                MemoRevisionLifecycleState.ACTIVE -> MaterialTheme.colorScheme.surfaceContainerHighest
                MemoRevisionLifecycleState.TRASHED -> MaterialTheme.colorScheme.tertiaryContainer
                MemoRevisionLifecycleState.DELETED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
            },
    ) {
        Text(
            text =
                when (lifecycleState) {
                    MemoRevisionLifecycleState.ACTIVE -> stringResource(R.string.memo_version_state_active)
                    MemoRevisionLifecycleState.TRASHED -> stringResource(R.string.memo_version_state_trashed)
                    MemoRevisionLifecycleState.DELETED -> stringResource(R.string.memo_version_state_deleted)
                },
            style = MaterialTheme.typography.labelSmall,
            color =
                when (lifecycleState) {
                    MemoRevisionLifecycleState.ACTIVE -> MaterialTheme.colorScheme.onSurfaceVariant
                    MemoRevisionLifecycleState.TRASHED -> MaterialTheme.colorScheme.onTertiaryContainer
                    MemoRevisionLifecycleState.DELETED -> MaterialTheme.colorScheme.onErrorContainer
                },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LoadMoreHistoryItem(
    isLoadingMore: Boolean,
    enabled: Boolean,
    onLoadMore: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(
                onClick = onLoadMore,
                enabled = enabled && !isLoadingMore,
            ) {
                Text(
                    text =
                        if (isLoadingMore) {
                            stringResource(R.string.memo_version_loading_more)
                        } else {
                            stringResource(R.string.memo_version_load_more)
                        },
                )
            }
        }
    }
}
