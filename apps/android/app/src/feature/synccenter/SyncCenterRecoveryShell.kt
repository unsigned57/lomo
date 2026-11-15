package com.lomo.app.feature.synccenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.RemoteSyncSessionPhase
import com.lomo.domain.model.RemoteSyncSessionProgress
import com.lomo.ui.component.common.LomoOutlinedButton
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

@Composable
internal fun SyncCenterRecoveryShell(session: RemoteSyncSessionProgress, onCancel: () -> Unit) {
    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("sync_center_recovery"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        Surface(
            shape = AppShapes.ExtraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            LomoIcon(
                                icon = LomoIcons.SettingsBackupRestore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.sync_center_recovery_heading),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        modifier = Modifier.weight(1f),
                    )
                }
                Surface(
                    shape = AppShapes.Medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = AppSpacing.MediumSmall, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                    ) {
                        LomoIcon(
                            icon = LomoIcons.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.sync_center_session_phase, session.phase.name),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.sync_center_recovery_shell_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (session.canCancel || session.phase == RemoteSyncSessionPhase.Failed) {
                    LomoOutlinedButton(
                        onClick = onCancel,
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                            .testTag("sync_center_recovery_cancel"),
                    ) {
                        LomoIcon(LomoIcons.Sync, contentDescription = null)
                        Spacer(Modifier.width(AppSpacing.Small))
                        Text(stringResource(R.string.sync_center_cancel_session))
                    }
                }
            }
        }
    }
}
