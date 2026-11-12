package com.lomo.app.feature.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.ui.component.common.LomoButton
import com.lomo.ui.component.common.LomoFilledTonalButton
import com.lomo.ui.component.common.LomoOutlinedButton
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

/**
 * Structured read-only recovery surface for engine failures, presented in the same M3 Expressive
 * empty-state language as the rest of the app: a tonal MaterialShapes icon container, an emphasized
 * title, the engine code as a metadata pill, and the recovery actions stacked below.
 *
 * No path continues writing through a Kotlin-owned core. Callers supply retry / reselect actions.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EngineReadOnlyRecoveryScreen(
    code: String,
    diagnostic: String,
    onRetry: () -> Unit,
    retryEnabled: Boolean,
    onReselectWorkspace: () -> Unit,
    canRebuildDerivedIndex: Boolean,
    onRebuildDerivedIndex: () -> Unit,
    onExportDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
        modifier
            .fillMaxSize()
            .padding(AppSpacing.Large),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                Modifier
                    .size(RecoveryTokens.IconContainerSize)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(RecoveryTokens.IconSize),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = stringResource(R.string.engine_recovery_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.engine_recovery_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Surface(
                shape = AppShapes.Full,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = stringResource(R.string.engine_recovery_code_label, code),
                    style = MaterialTheme.typography.labelLarge,
                    modifier =
                    Modifier.padding(
                        horizontal = RecoveryTokens.CodePillHorizontalPadding,
                        vertical = RecoveryTokens.CodePillVerticalPadding,
                    ),
                )
            }
            Text(
                text = diagnostic,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (canRebuildDerivedIndex) {
                LomoButton(onClick = onRebuildDerivedIndex) {
                    Text(stringResource(R.string.engine_recovery_rebuild_index))
                }
            }
            LomoButton(
                onClick = onRetry,
                enabled = retryEnabled,
            ) {
                Text(stringResource(R.string.engine_recovery_retry))
            }
            if (!retryEnabled) {
                Text(
                    text = stringResource(R.string.engine_recovery_retry_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            LomoFilledTonalButton(onClick = onExportDiagnostics) {
                Text(stringResource(R.string.engine_recovery_export_diagnostics))
            }
            LomoOutlinedButton(onClick = onReselectWorkspace) {
                Text(stringResource(R.string.engine_recovery_reselect))
            }
        }
    }
}

private object RecoveryTokens {
    val IconContainerSize = 96.dp
    val IconSize = 40.dp
    val CodePillHorizontalPadding = AppSpacing.MediumSmall
    val CodePillVerticalPadding = AppSpacing.Small
}
