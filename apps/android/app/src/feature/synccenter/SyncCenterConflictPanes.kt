package com.lomo.app.feature.synccenter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictSessionState
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.component.common.LomoButton
import com.lomo.ui.component.common.LomoOutlinedButton
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

private val SyncCenterDetailBackIconSize = 18.dp

private const val LIST_DETAIL_LIST_WEIGHT = 0.4f
private const val LIST_DETAIL_DETAIL_WEIGHT = 0.6f

internal fun isConflictDetailLoading(ready: SyncCenterLoadState.Ready, path: RemoteSyncConflictPath): Boolean {
    if (!ready.isLoadingDetail) return false
    val waitingMarkdown = path.isMarkdown && ready.markdownDetailByPath[path.path] == null
    val waitingBinary = path.isBinary && ready.binaryDetailByPath[path.path] == null
    return waitingMarkdown || waitingBinary
}

@Composable
internal fun SyncCenterListDetailPane(ready: SyncCenterLoadState.Ready, onIntent: (SyncCenterIntent) -> Unit) {
    Row(
        modifier =
        Modifier
            .fillMaxSize()
            .testTag("sync_center_list_detail"),
    ) {
        Box(
            modifier =
            Modifier
                .weight(LIST_DETAIL_LIST_WEIGHT)
                .fillMaxHeight(),
        ) {
            SyncCenterConflictListPane(ready = ready, onIntent = onIntent)
        }
        VerticalDivider(modifier = Modifier.fillMaxHeight())
        Box(
            modifier =
            Modifier
                .weight(LIST_DETAIL_DETAIL_WEIGHT)
                .fillMaxHeight()
                .padding(start = AppSpacing.Medium),
        ) {
            val selected = ready.items.firstOrNull { it.path == ready.selectedPath }
            if (selected == null) {
                Text(
                    text = stringResource(R.string.sync_center_select_conflict_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                SyncCenterConflictDetailPane(
                    ready = ready,
                    path = selected,
                    onIntent = onIntent,
                    showBack = false,
                )
            }
        }
    }
}

@Composable
internal fun SyncCenterConflictListPane(ready: SyncCenterLoadState.Ready, onIntent: (SyncCenterIntent) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().testTag("sync_center_conflict_list")) {
        when (ready.conflictPage.session) {
            RemoteSyncConflictSessionState.Absent -> {
                AbsentConflictSession()
            }

            RemoteSyncConflictSessionState.Present -> {
                ConflictSessionSummary(ready)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                ) {
                    items(ready.items, key = { it.path }) { item ->
                        SyncCenterConflictRow(
                            item = item,
                            selected = item.path == ready.selectedPath,
                            onClick = { onIntent(SyncCenterIntent.SelectConflict(item.path)) },
                        )
                    }
                    if (ready.conflictPage.nextCursor != null) {
                        item(key = "load_more") {
                            LomoOutlinedButton(
                                onClick = { onIntent(SyncCenterIntent.LoadMoreConflicts) },
                                enabled = !ready.isResolving,
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                                    .testTag("sync_center_load_more"),
                            ) {
                                Text(stringResource(R.string.sync_center_load_more))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(AppSpacing.Small))
                LomoButton(
                    onClick = { onIntent(SyncCenterIntent.ApplyResolutions) },
                    enabled = !ready.isResolving && ready.perPathResolutionKind.isNotEmpty(),
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                        .testTag("sync_center_apply_resolutions"),
                ) {
                    if (ready.isResolving) {
                        ExpressiveLoadingIndicator(
                            modifier = Modifier.height(24.dp).width(24.dp),
                        )
                        Spacer(Modifier.width(AppSpacing.Small))
                    }
                    Text(stringResource(R.string.sync_center_apply_resolutions))
                }
            }
        }
    }
}

@Composable
internal fun SyncCenterConflictRow(item: RemoteSyncConflictPath, selected: Boolean, onClick: () -> Unit) {
    val description =
        stringResource(
            R.string.sync_center_conflict_row_a11y,
            item.path,
            item.kind,
            item.status.name,
        )
    Surface(
        shape = AppShapes.LargeIncreased,
        color =
        if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier =
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .testTag("sync_center_conflict_row_${item.path}"),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        LomoIcon(
                            icon =
                            if (item.isBinary) {
                                LomoIcons.InsertDriveFile
                            } else {
                                LomoIcons.Description
                            },
                            contentDescription = null,
                            modifier = Modifier.size(SyncCenterConflictRowIconSize),
                            tint =
                            if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            },
            supportingContent = {
                Text(
                    text =
                    stringResource(
                        R.string.sync_center_conflict_row_subtitle,
                        item.kind,
                        item.status.name,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) {
            Text(
                text = item.path,
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val SyncCenterConflictRowIconSize = 20.dp

@Composable
internal fun SyncCenterConflictDetailPane(
    ready: SyncCenterLoadState.Ready,
    path: RemoteSyncConflictPath?,
    onIntent: (SyncCenterIntent) -> Unit,
    showBack: Boolean,
) {
    if (path == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.sync_center_select_conflict_prompt))
        }
        return
    }
    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("sync_center_conflict_detail"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        if (showBack) {
            LomoTextButton(
                onClick = { onIntent(SyncCenterIntent.ClearSelection) },
                modifier =
                Modifier
                    .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                    .testTag("sync_center_detail_back"),
            ) {
                LomoIcon(
                    icon = LomoIcons.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(SyncCenterDetailBackIconSize),
                )
                Spacer(Modifier.width(AppSpacing.Small))
                Text(stringResource(R.string.sync_center_back_to_list))
            }
        }
        ConflictDetailHeader(path)
        if (isConflictDetailLoading(ready, path)) {
            ExpressiveContainedLoadingIndicator(
                modifier = Modifier.testTag("sync_center_detail_loading"),
            )
        }
        if (path.isBinary) {
            // Prefer repository-loaded facts (live path); digest-only helper is fallback only.
            BinaryConflictDetail(facts = binaryFactsFromState(ready, path))
        } else if (path.isMarkdown) {
            MarkdownConflictDetailShell(
                facts = markdownFactsFromState(ready, path),
                draft = ready.mergedDrafts[path.path].orEmpty(),
                onDraftChange = { draft ->
                    onIntent(SyncCenterIntent.SetMergedDraft(path.path, draft))
                },
            )
        } else {
            Text(
                text = stringResource(R.string.sync_center_unknown_kind),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(R.string.sync_center_resolution_heading),
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
        ResolutionKindChips(
            path = path.path,
            selectedKind = ready.perPathResolutionKind[path.path],
            isBinary = path.isBinary,
            enabled = !ready.isResolving,
            onSelect = { kind -> onIntent(SyncCenterIntent.SetResolutionKind(path.path, kind)) },
        )
    }
}

@Composable
private fun AbsentConflictSession() {
    Surface(
        shape = AppShapes.ExtraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LomoIcon(
                        icon = LomoIcons.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Text(
                text = stringResource(R.string.sync_center_conflict_session_absent),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("sync_center_conflict_session_absent"),
            )
        }
    }
}

@Composable
private fun ConflictSessionSummary(ready: SyncCenterLoadState.Ready) {
    // One top-level emitter: the summary chip and the optional error banner are one block.
    Column {
        Surface(
            shape = AppShapes.Full,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier =
                Modifier.padding(
                    horizontal = AppSpacing.MediumSmall,
                    vertical = 6.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LomoIcon(
                    icon = LomoIcons.CompareArrows,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text =
                    stringResource(
                        R.string.sync_center_conflict_page_meta,
                        ready.conflictPage.sessionId,
                        ready.conflictPage.conflictRevision,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.Small))
        ready.lastError?.let { error ->
            Surface(
                shape = AppShapes.Medium,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                ) {
                    LomoIcon(
                        icon = LomoIcons.SyncProblem,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = stringResource(R.string.sync_center_error, error),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("sync_center_list_error"),
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.Small))
        }
        if (ready.items.isEmpty()) {
            Surface(
                shape = AppShapes.ExtraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(AppSpacing.Large),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
                ) {
                    LomoIcon(
                        icon = LomoIcons.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.sync_center_conflict_session_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("sync_center_conflict_session_empty"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConflictDetailHeader(path: RemoteSyncConflictPath) {
    Surface(
        shape = AppShapes.ExtraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LomoIcon(
                        icon =
                        if (path.isBinary) {
                            LomoIcons.InsertDriveFile
                        } else {
                            LomoIcons.Description
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = path.path, style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    text = stringResource(R.string.sync_center_conflict_kind, path.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
