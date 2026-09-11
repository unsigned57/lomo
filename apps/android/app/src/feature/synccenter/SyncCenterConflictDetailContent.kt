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

@Composable
internal fun BinaryConflictDetail(facts: RemoteSyncBinaryConflictFacts) {
    Column(
        modifier = Modifier.testTag("sync_center_binary_detail"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        Text(
            text = stringResource(R.string.sync_center_binary_no_preview),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                stringResource(
                    R.string.sync_center_binary_mime,
                    facts.mimeType ?: stringResource(R.string.sync_center_value_unknown),
                ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text =
                stringResource(
                    R.string.sync_center_binary_size,
                    facts.sizeBytes?.toString()
                        ?: stringResource(R.string.sync_center_value_unknown),
                ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text =
                stringResource(
                    R.string.sync_center_digest_local,
                    facts.localDigest ?: stringResource(R.string.sync_center_value_unknown),
                ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("sync_center_binary_local_digest"),
        )
        Text(
            text =
                stringResource(
                    R.string.sync_center_digest_remote,
                    facts.remoteDigest ?: stringResource(R.string.sync_center_value_unknown),
                ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text =
                stringResource(
                    R.string.sync_center_binary_source,
                    facts.sourceLabel,
                ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun MarkdownConflictDetailShell(
    facts: RemoteSyncMarkdownConflictFacts,
    draft: String,
    onDraftChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.testTag("sync_center_markdown_detail"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        Text(
            text = stringResource(R.string.sync_center_markdown_shell_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                stringResource(
                    R.string.sync_center_digest_base,
                    facts.baseDigest ?: stringResource(R.string.sync_center_value_unknown),
                ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text =
                stringResource(
                    R.string.sync_center_digest_local,
                    facts.localDigest ?: stringResource(R.string.sync_center_value_unknown),
                ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text =
                stringResource(
                    R.string.sync_center_digest_remote,
                    facts.remoteDigest ?: stringResource(R.string.sync_center_value_unknown),
                ),
            style = MaterialTheme.typography.bodySmall,
        )
        MarkdownBodySection(
            title = stringResource(R.string.sync_center_markdown_body_base),
            body = facts.baseBody,
        )
        MarkdownBodySection(
            title = stringResource(R.string.sync_center_markdown_body_local),
            body = facts.localBody,
        )
        MarkdownBodySection(
            title = stringResource(R.string.sync_center_markdown_body_remote),
            body = facts.remoteBody,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text(stringResource(R.string.sync_center_merged_editor_label)) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 120.dp)
                    .testTag("sync_center_merged_editor"),
            minLines = 4,
        )
    }
}


@Composable
internal fun MarkdownBodySection(
    title: String,
    body: String?,
) {
    Text(text = title, style = MaterialTheme.typography.titleSmall)
    if (body == null) {
        Text(
            text = stringResource(R.string.sync_center_markdown_body_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("sync_center_markdown_body"),
        )
    }
}

@Composable
internal fun ResolutionKindChips(
    path: String,
    selectedKind: String?,
    isBinary: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val kinds =
        buildList {
            add(RemoteSyncConflictResolution.KIND_KEEP_LOCAL)
            add(RemoteSyncConflictResolution.KIND_KEEP_REMOTE)
            if (!isBinary) {
                add(RemoteSyncConflictResolution.KIND_MERGED_BODY)
            }
            add(RemoteSyncConflictResolution.KIND_SKIP_FOR_NOW)
        }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        kinds.forEach { kind ->
            FilterChip(
                selected = selectedKind == kind,
                onClick = { onSelect(kind) },
                enabled = enabled,
                label = { Text(resolutionKindLabel(kind)) },
                modifier =
                    Modifier
                        .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                        .testTag("sync_center_resolution_${path}_$kind"),
            )
        }
    }
}

@Composable
internal fun resolutionKindLabel(kind: String): String =
    when (kind) {
        RemoteSyncConflictResolution.KIND_KEEP_LOCAL ->
            stringResource(R.string.sync_center_resolution_keep_local)
        RemoteSyncConflictResolution.KIND_KEEP_REMOTE ->
            stringResource(R.string.sync_center_resolution_keep_remote)
        RemoteSyncConflictResolution.KIND_MERGED_BODY ->
            stringResource(R.string.sync_center_resolution_merged)
        RemoteSyncConflictResolution.KIND_SKIP_FOR_NOW ->
            stringResource(R.string.sync_center_resolution_skip)
        else -> kind
    }

