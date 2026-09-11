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
internal fun SyncCenterOverviewPane(
    config: RemoteSyncConfigSummary,
    session: RemoteSyncSessionProgress,
    onOpenConflicts: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag("sync_center_overview"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        Text(
            text = stringResource(R.string.sync_center_config_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text =
                stringResource(
                    R.string.sync_center_backend_label,
                    config.backend.name,
                ),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text =
                stringResource(
                    R.string.sync_center_attention_count,
                    config.attentionCount,
                ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("sync_center_attention_count"),
        )
        val lastVerified = config.lastVerifiedAtEpochMillis
        Text(
            text =
                if (lastVerified == null) {
                    stringResource(R.string.sync_center_last_verified_never)
                } else {
                    stringResource(R.string.sync_center_last_verified_at, lastVerified.toString())
                },
            style = MaterialTheme.typography.bodyMedium,
        )
        config.schedulePolicyLabel?.let { policy ->
            Text(
                text = stringResource(R.string.sync_center_schedule_policy, policy),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HorizontalDivider()
        Text(
            text = stringResource(R.string.sync_center_session_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.sync_center_session_phase, session.phase.name),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("sync_center_session_phase"),
        )
        val total = session.totalActions
        if (total != null) {
            Text(
                text =
                    stringResource(
                        R.string.sync_center_session_progress,
                        session.completedActions,
                        total,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            Button(
                onClick = onOpenConflicts,
                modifier =
                    Modifier
                        .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                        .testTag("sync_center_open_conflicts"),
            ) {
                Text(stringResource(R.string.sync_center_open_conflicts))
            }
            if (session.canCancel) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier =
                        Modifier
                            .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                            .testTag("sync_center_cancel_session"),
                ) {
                    Icon(Icons.Outlined.Cancel, contentDescription = null)
                    Spacer(Modifier.width(AppSpacing.Small))
                    Text(stringResource(R.string.sync_center_cancel_session))
                }
            }
        }
    }
}

