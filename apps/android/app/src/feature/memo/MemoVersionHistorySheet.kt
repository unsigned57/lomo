package com.lomo.app.feature.memo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.presentation.markdown.MemoMarkdownMediaContent
import com.lomo.app.presentation.markdown.MemoMarkdownMediaPresentationResolver
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.component.common.LomoModalBottomSheet
import com.lomo.ui.component.common.LomoSheetTokens
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.component.common.rememberModalSheetPresentation
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.LocalAppHapticFeedback
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

internal fun resolveVersionHistoryCardPresentation(
    version: MemoRevision,
    isRestoreInProgress: Boolean,
    restoringRevisionId: String?,
): VersionHistoryCardPresentation = VersionHistoryCardPresentation(
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
    val presentation = rememberModalSheetPresentation()

    LomoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = presentation.sheetState,
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged(presentation::onContentMeasured)
                .benchmarkAnchorRoot(BenchmarkAnchorContract.VERSION_HISTORY_ROOT)
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .padding(bottom = 24.dp),
        ) {
            MemoVersionHistoryHeader()
            Spacer(modifier = Modifier.height(16.dp))
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MemoVersionHistoryHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialShapes.Cookie9Sided.toShape(),
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                LomoIcon(
                    icon = LomoIcons.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.memo_version_history),
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
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
                    val formattedTime =
                        Instant
                            .ofEpochMilli(version.revision.createdAt)
                            .atZone(ZoneId.systemDefault())
                            .format(VERSION_COMMIT_TIME_FORMATTER)
                    VersionTimelineItem(
                        version = version.revision,
                        preview = version.preview,
                        formattedTime = formattedTime,
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
private fun MemoVersionHistoryPlaceholder(text: String, showLoadingIndicator: Boolean) {
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
    preview: MemoVersionPreview,
    formattedTime: String,
    isRestoreInProgress: Boolean,
    restoringRevisionId: String?,
    onRestore: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    val componentMotion = LocalLomoMotion.current.component
    val presentation =
        resolveVersionHistoryCardPresentation(
            version = version,
            isRestoreInProgress = isRestoreInProgress,
            restoringRevisionId = restoringRevisionId,
        )
    val containerColor by animateColorAsState(
        targetValue =
        if (presentation.highlight == VersionHistoryCardHighlight.Current) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            LomoSheetTokens.tileSurfaceColor(MaterialTheme.colorScheme)
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "VersionItemContainer",
    )
    val topMetaColor by animateColorAsState(
        targetValue =
        if (presentation.highlight == VersionHistoryCardHighlight.Current) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "VersionItemMeta",
    )

    when (presentation.interaction) {
        VersionHistoryCardInteraction.Restore -> {
            Card(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .benchmarkAnchor(BenchmarkAnchorContract.versionHistoryRestore(version.revisionId)),
                shape = LomoSheetTokens.TileShape,
                colors = CardDefaults.cardColors(containerColor = containerColor),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    VersionTimelineItemBody(
                        formattedTime = formattedTime,
                        topMetaColor = topMetaColor,
                        isBusy = presentation.isBusy,
                        lifecycleState = version.lifecycleState,
                        preview = preview,
                    )
                    Box(
                        modifier =
                        Modifier
                            .matchParentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    haptic.medium()
                                    onRestore()
                                },
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
                shape = LomoSheetTokens.TileShape,
                colors = CardDefaults.cardColors(containerColor = containerColor),
            ) {
                VersionTimelineItemBody(
                    formattedTime = formattedTime,
                    topMetaColor = topMetaColor,
                    isBusy = presentation.isBusy,
                    lifecycleState = version.lifecycleState,
                    preview = preview,
                )
            }
        }
    }
}

@Composable
private fun VersionTimelineItemBody(
    formattedTime: String,
    topMetaColor: androidx.compose.ui.graphics.Color,
    isBusy: Boolean,
    lifecycleState: MemoRevisionLifecycleState,
    preview: MemoVersionPreview,
) {
    val componentMotion = LocalLomoMotion.current.component
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
                AnimatedVisibility(
                    visible = isBusy,
                    enter = fadeIn(componentMotion.fastEffectsSpec()) +
                        scaleIn(initialScale = 0.85f, animationSpec = componentMotion.fastSpatialSpec()),
                    exit = fadeOut(componentMotion.fastEffectsSpec()) +
                        scaleOut(targetScale = 0.85f, animationSpec = componentMotion.fastSpatialSpec()),
                ) {
                    VersionRestoringBadge()
                }
                if (lifecycleState != MemoRevisionLifecycleState.ACTIVE) {
                    VersionLifecycleBadge(lifecycleState = lifecycleState)
                }
            }
        }
        VersionContentPreview(preview = preview)
    }
}

@Composable
private fun VersionContentPreview(preview: MemoVersionPreview) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (preview) {
            is MemoVersionPreview.Rendered ->
                MarkdownRenderer(
                    document = preview.document,
                    modifier = Modifier.fillMaxWidth(),
                    maxVisibleBlocks = VERSION_PREVIEW_MAX_VISIBLE_BLOCKS,
                    mediaPresentationResolver = MemoMarkdownMediaPresentationResolver,
                    mediaContent = { presentation -> MemoMarkdownMediaContent(presentation) },
                )

            is MemoVersionPreview.Unavailable ->
                Text(
                    text = stringResource(R.string.memo_version_preview_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
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
private fun LoadMoreHistoryItem(isLoadingMore: Boolean, enabled: Boolean, onLoadMore: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = LomoSheetTokens.tileSurfaceColor(MaterialTheme.colorScheme),
        shape = LomoSheetTokens.TileShape,
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            LomoTextButton(
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
