package com.lomo.app.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R

/**
 * Structured read-only recovery surface for engine failures.
 *
 * No path continues writing through a Kotlin-owned core. Callers supply retry / reselect actions.
 */
@Composable
fun EngineReadOnlyRecoveryScreen(
    code: String,
    diagnostic: String,
    onRetry: () -> Unit,
    onReselectWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.engine_recovery_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.engine_recovery_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.engine_recovery_code_label, code),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = diagnostic,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.engine_recovery_retry))
        }
        OutlinedButton(onClick = onReselectWorkspace) {
            Text(stringResource(R.string.engine_recovery_reselect))
        }
    }
}
