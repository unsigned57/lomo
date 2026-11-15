package com.lomo.app.feature.synccenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncConflictSessionState
import com.lomo.domain.model.RemoteSyncSessionPhase
import com.lomo.domain.model.RemoteSyncSessionProgress
import com.lomo.ui.component.common.LomoButton
import com.lomo.ui.component.common.LomoOutlinedButton
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIconSpec
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

/**
 * Sync Center overview: three expressive M3E cards (session hero, configuration bento, conflicts)
 * with a wavy M3 Expressive progress indicator for a live session and durable facts styled as
 * tonal tiles.
 */
@Composable
internal fun SyncCenterOverviewPane(
    config: RemoteSyncConfigSummary,
    session: RemoteSyncSessionProgress,
    conflictPage: RemoteSyncConflictPage,
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
        SyncCenterSessionCard(session = session, onCancel = onCancel)
        SyncCenterConfigCard(config)
        SyncCenterConflictsCard(
            conflictPage = conflictPage,
            onOpenConflicts = onOpenConflicts,
        )
    }
}

@Composable
private fun SyncCenterCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.ExtraLarge,
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            content = content,
        )
    }
}

@Composable
private fun SyncCenterCardHeading(
    icon: LomoIconSpec,
    title: String,
    badgeColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = badgeColor,
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                LomoIcon(
                    icon = icon,
                    contentDescription = null,
                    modifier = Modifier.size(SyncCenterCardIconSize),
                    tint = iconTint,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMediumEmphasized,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun SyncCenterFactRow(
    icon: LomoIconSpec,
    text: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Surface(
        shape = AppShapes.Medium,
        color =
        if (highlight) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.MediumSmall, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            LomoIcon(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(SyncCenterFactIconSize),
                tint =
                if (highlight) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
                color =
                if (highlight) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun SyncCenterConfigCard(config: RemoteSyncConfigSummary) {
    SyncCenterCard {
        SyncCenterCardHeading(
            icon = LomoIcons.Cloud,
            title = stringResource(R.string.sync_center_config_heading),
        )
        SyncCenterFactRow(
            icon = LomoIcons.Sync,
            text = stringResource(R.string.sync_center_backend_label, config.backend.name),
        )
        SyncCenterFactRow(
            icon = LomoIcons.NotificationImportant,
            text = stringResource(R.string.sync_center_attention_count, config.attentionCount),
            highlight = config.attentionCount > 0,
            modifier = Modifier.testTag("sync_center_attention_count"),
        )
        val lastVerified = config.lastVerifiedAtEpochMillis
        SyncCenterFactRow(
            icon = LomoIcons.Verified,
            text =
            if (lastVerified == null) {
                stringResource(R.string.sync_center_last_verified_never)
            } else {
                stringResource(R.string.sync_center_last_verified_at, lastVerified.toString())
            },
        )
        config.schedulePolicyLabel?.let { policy ->
            SyncCenterFactRow(
                icon = LomoIcons.Schedule,
                text = stringResource(R.string.sync_center_schedule_policy, policy),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncCenterSessionCard(session: RemoteSyncSessionProgress, onCancel: () -> Unit) {
    val inFlight = isSessionInFlight(session)
    val isFailed = session.phase == RemoteSyncSessionPhase.Failed
    SyncCenterCard(
        containerColor =
        when {
            isFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
            inFlight -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        SyncCenterCardHeading(
            icon = LomoIcons.Timeline,
            title = stringResource(R.string.sync_center_session_heading),
            badgeColor =
            if (isFailed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            iconTint =
            if (isFailed) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        )
        SyncCenterFactRow(
            icon = LomoIcons.Sync,
            text = stringResource(R.string.sync_center_session_phase, session.phase.name),
            modifier = Modifier.testTag("sync_center_session_phase"),
        )
        val total = session.totalActions
        when {
            total != null && total > 0 ->
                LinearWavyProgressIndicator(
                    progress = {
                        (session.completedActions.toFloat() / total).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

            inFlight ->
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())

            else -> Unit
        }
        if (total != null) {
            Surface(
                shape = AppShapes.Full,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text =
                    stringResource(
                        R.string.sync_center_session_progress,
                        session.completedActions,
                        total,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = 4.dp),
                )
            }
        }
        if (session.canCancel) {
            LomoOutlinedButton(
                onClick = onCancel,
                modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                    .testTag("sync_center_cancel_session"),
            ) {
                LomoIcon(LomoIcons.Cancel, contentDescription = null)
                Spacer(Modifier.size(AppSpacing.Small))
                Text(stringResource(R.string.sync_center_cancel_session))
            }
        }
    }
}

private fun isSessionInFlight(session: RemoteSyncSessionProgress): Boolean = session.canCancel ||
    session.phase in
    setOf(
        RemoteSyncSessionPhase.Preflight,
        RemoteSyncSessionPhase.Snapshot,
        RemoteSyncSessionPhase.Plan,
        RemoteSyncSessionPhase.Apply,
        RemoteSyncSessionPhase.Verify,
        RemoteSyncSessionPhase.ConflictOpen,
        RemoteSyncSessionPhase.Cancelling,
    )

@Composable
private fun SyncCenterConflictsCard(conflictPage: RemoteSyncConflictPage, onOpenConflicts: () -> Unit) {
    val hasActiveConflicts =
        conflictPage.session == RemoteSyncConflictSessionState.Present && conflictPage.items.isNotEmpty()
    val conflictStatus =
        when (conflictPage.session) {
            RemoteSyncConflictSessionState.Absent ->
                R.string.sync_center_conflict_session_absent to "sync_center_conflict_session_absent"

            RemoteSyncConflictSessionState.Present ->
                if (conflictPage.items.isEmpty()) {
                    R.string.sync_center_conflict_session_empty to "sync_center_conflict_session_empty"
                } else {
                    R.string.sync_center_open_conflicts to "sync_center_conflict_session_present"
                }
        }
    SyncCenterCard(
        containerColor =
        if (hasActiveConflicts) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        SyncCenterCardHeading(
            icon = LomoIcons.CompareArrows,
            title = stringResource(conflictStatus.first),
            badgeColor =
            if (hasActiveConflicts) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            iconTint =
            if (hasActiveConflicts) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            trailing =
            if (hasActiveConflicts) {
                {
                    Surface(
                        shape = AppShapes.Full,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = conflictPage.items.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            } else {
                null
            },
        )
        LomoButton(
            onClick = onOpenConflicts,
            modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                .testTag("sync_center_open_conflicts"),
        ) {
            LomoIcon(LomoIcons.CompareArrows, contentDescription = null)
            Spacer(Modifier.size(AppSpacing.Small))
            Text(stringResource(R.string.sync_center_open_conflicts))
        }
    }
}

private val SyncCenterCardIconSize = 20.dp
private val SyncCenterFactIconSize = 18.dp
