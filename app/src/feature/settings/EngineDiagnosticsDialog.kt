package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import org.koin.compose.koinInject

/**
 * Debug-only window onto the engine diagnostics channel.
 *
 * A rejection is rendered by its stable code and category; the raw diagnostic is shown here and
 * nowhere else, because it can quote workspace paths and memo text.
 */
@Composable
internal fun EngineDiagnosticsDialog(onDismiss: () -> Unit) {
    val recorder = koinInject<EngineDiagnosticsRecorder>()
    val events by recorder.events.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        title = { Text(stringResource(R.string.settings_debug_engine_diagnostics)) },
        text = {
            if (events.isEmpty()) {
                Text(stringResource(R.string.settings_debug_engine_diagnostics_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(events) { event ->
                        EngineDiagnosticEventRow(event)
                    }
                }
            }
        },
    )
}

@Composable
private fun EngineDiagnosticEventRow(event: EngineDiagnosticEvent) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text =
                stringResource(
                    R.string.settings_debug_engine_diagnostics_entry,
                    event.label,
                    event.durationMillis,
                ),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = event.detail(),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun EngineDiagnosticEvent.detail(): String =
    when (this) {
        is EngineDiagnosticEvent.Committed ->
            "committed · revision=$coreRevision · scopes=${scopes.joinToString(",").ifEmpty { "-" }}"
        is EngineDiagnosticEvent.Rejected ->
            buildString {
                append("rejected · ${failure.code} · ${failure.category.wireValue}")
                append(" · retry=${failure.retryDisposition.wireValue}")
                failure.operationId?.let { append(" · op=$it") }
                failure.jobId?.let { append(" · job=$it") }
                if (failure.diagnostic.isNotBlank()) {
                    append("\n${failure.diagnostic}")
                }
            }
        is EngineDiagnosticEvent.Stalled ->
            "stalled · no terminal acknowledgement within the submission budget"
    }
