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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.lomo.app.R
import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.domain.model.RemoteSyncSessionPhase
import com.lomo.domain.model.RemoteSyncSessionProgress
import com.lomo.ui.theme.AppSpacing

private const val LIST_DETAIL_LIST_WEIGHT = 0.4f
private const val LIST_DETAIL_DETAIL_WEIGHT = 0.6f


internal fun isConflictDetailLoading(
    ready: SyncCenterLoadState.Ready,
    path: RemoteSyncConflictPath,
): Boolean {
    if (!ready.isLoadingDetail) return false
    val waitingMarkdown = path.isMarkdown && ready.markdownDetailByPath[path.path] == null
    val waitingBinary = path.isBinary && ready.binaryDetailByPath[path.path] == null
    return waitingMarkdown || waitingBinary
}

@Composable
internal fun SyncCenterListDetailPane(
    ready: SyncCenterLoadState.Ready,
    onIntent: (SyncCenterIntent) -> Unit,
) {
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
internal fun SyncCenterConflictListPane(
    ready: SyncCenterLoadState.Ready,
    onIntent: (SyncCenterIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().testTag("sync_center_conflict_list")) {
        Text(
            text =
                stringResource(
                    R.string.sync_center_conflict_page_meta,
                    ready.conflictPage.sessionId,
                    ready.conflictPage.conflictRevision,
                ),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(AppSpacing.Small))
        ready.lastError?.let { error ->
            Text(
                text = stringResource(R.string.sync_center_error, error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("sync_center_list_error"),
            )
            Spacer(Modifier.height(AppSpacing.Small))
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
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
                    OutlinedButton(
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
        Button(
            onClick = { onIntent(SyncCenterIntent.ApplyResolutions) },
            enabled = !ready.isResolving && ready.perPathResolutionKind.isNotEmpty(),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                    .testTag("sync_center_apply_resolutions"),
        ) {
            if (ready.isResolving) {
                CircularProgressIndicator(
                    modifier = Modifier.height(24.dp).width(24.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(AppSpacing.Small))
            }
            Text(stringResource(R.string.sync_center_apply_resolutions))
        }
    }
}

@Composable
internal fun SyncCenterConflictRow(
    item: RemoteSyncConflictPath,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val description =
        stringResource(
            R.string.sync_center_conflict_row_a11y,
            item.path,
            item.kind,
            item.status.name,
        )
    Surface(
        tonalElevation = if (selected) 3.dp else 0.dp,
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
        Column(modifier = Modifier.padding(AppSpacing.MediumSmall)) {
            Text(text = item.path, style = MaterialTheme.typography.titleSmall)
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
        }
    }
}

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
            OutlinedButton(
                onClick = { onIntent(SyncCenterIntent.ClearSelection) },
                modifier =
                    Modifier
                        .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                        .testTag("sync_center_detail_back"),
            ) {
                Text(stringResource(R.string.sync_center_back_to_list))
            }
        }
        Text(text = path.path, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.sync_center_conflict_kind, path.kind),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (isConflictDetailLoading(ready, path)) {
            CircularProgressIndicator(
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
            style = MaterialTheme.typography.titleSmall,
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

