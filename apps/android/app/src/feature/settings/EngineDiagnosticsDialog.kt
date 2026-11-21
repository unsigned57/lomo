package com.lomo.app.feature.settings

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.summary
import com.lomo.ui.component.common.LomoTextButton
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun EngineDiagnosticsDialog(appVersion: String, onDismiss: () -> Unit) {
    val recorder = koinInject<EngineDiagnosticsRecorder>()
    val events by recorder.events.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.settings_engine_diagnostics)
    val version = appVersion.ifBlank { stringResource(R.string.settings_current_version_unknown) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            LomoTextButton(enabled = events.isNotEmpty(), onClick = {
                val report = recentDiagnosticsReport(events, version, Build.VERSION.SDK_INT)
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(title, report))) }
            }) { Text(stringResource(R.string.settings_engine_diagnostics_copy)) }
        },
        dismissButton = {
            LomoTextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        title = { Text(title) },
        text = {
            if (events.isEmpty()) {
                Text(stringResource(R.string.settings_engine_diagnostics_empty))
            } else {
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(events) { event ->
                            Text(event.summary(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
    )
}

internal fun recentDiagnosticsReport(
    events: List<EngineDiagnosticEvent>,
    appVersion: String,
    androidSdk: Int,
): String = buildString {
    appendLine("lomo_diagnostics_v1")
    appendLine("app_version=$appVersion")
    appendLine("android_sdk=$androidSdk")
    events.forEach { appendLine(it.summary()) }
}
