package com.lomo.app.feature.settings

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.common.failureExplanation
import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.model.isError
import com.lomo.domain.model.redactedExtraLines
import com.lomo.domain.model.sanitizeDiagnosticText
import com.lomo.domain.model.summary
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class DiagnosticsFilter {
    ERRORS_ONLY,
    ALL,
}

internal data class DiagnosticsEnvironmentSnapshot(
    val appVersion: String,
    val androidSdk: Int,
    val androidRelease: String,
    val device: String,
    val abis: String,
    val locale: String,
    val timeZoneId: String,
    val timeZoneOffset: String,
    val workspaceKind: String,
    val imageStorageKind: String,
    val voiceStorageKind: String,
    val filenameFormat: String,
    val timestampFormat: String,
    val engineReadiness: String,
    val projectionFreshness: String,
    val recoveryFailure: EngineReadiness.ReadOnlyRecovery? = null,
)

@Composable
internal fun EngineDiagnosticsDialog(
    appVersion: String,
    filenameFormat: String,
    timestampFormat: String,
    onDismiss: () -> Unit,
) {
    val recorder = koinInject<EngineDiagnosticsRecorder>()
    val readinessRepository = koinInject<EngineReadinessRepository>()
    val events by recorder.events.collectAsStateWithLifecycle()
    val mount by readinessRepository.mount.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.settings_engine_diagnostics)
    val version = appVersion.ifBlank { stringResource(R.string.settings_current_version_unknown) }
    val environment =
        remember(version, mount, filenameFormat, timestampFormat) {
            buildDiagnosticsEnvironmentSnapshot(
                appVersion = version,
                mount = mount,
                filenameFormat = filenameFormat,
                timestampFormat = timestampFormat,
            )
        }
    val errorCount = remember(events) { events.count { it.isError } }
    var filter by remember {
        mutableStateOf(
            if (errorCount > 0 || mount.readiness is EngineReadiness.ReadOnlyRecovery) {
                DiagnosticsFilter.ERRORS_ONLY
            } else {
                DiagnosticsFilter.ALL
            },
        )
    }
    var copiedFeedback by remember { mutableStateOf(false) }
    LaunchedEffect(copiedFeedback) {
        if (copiedFeedback) {
            delay(COPIED_FEEDBACK_MILLIS)
            copiedFeedback = false
        }
    }
    val visibleEvents =
        remember(events, filter) {
            when (filter) {
                DiagnosticsFilter.ERRORS_ONLY -> events.filter { it.isError }
                DiagnosticsFilter.ALL -> events
            }
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        EngineDiagnosticsDialogContent(
            title = title,
            environment = environment,
            filter = filter,
            errorCount = errorCount,
            totalCount = events.size,
            visibleEvents = visibleEvents,
            copiedFeedback = copiedFeedback,
            onSelectFilter = { filter = it },
            onClear = recorder::clear,
            onDismiss = onDismiss,
            onCopyReport = {
                val report = recentDiagnosticsReport(events, environment)
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(title, report)))
                    copiedFeedback = true
                }
            },
        )
    }
}

@Composable
private fun EngineDiagnosticsDialogContent(
    title: String,
    environment: DiagnosticsEnvironmentSnapshot,
    filter: DiagnosticsFilter,
    errorCount: Int,
    totalCount: Int,
    visibleEvents: List<EngineDiagnosticEvent>,
    copiedFeedback: Boolean,
    onSelectFilter: (DiagnosticsFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onCopyReport: () -> Unit,
) {
    Surface(
        modifier =
        Modifier
            .fillMaxWidth(DIALOG_WIDTH_FRACTION)
            .widthIn(max = 560.dp)
            .heightIn(max = 680.dp),
        shape = AppShapes.ExtraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            DiagnosticsEnvironmentCard(environment = environment)

            DiagnosticsFilterBar(
                filter = filter,
                errorCount = errorCount,
                totalCount = totalCount,
                onSelectFilter = onSelectFilter,
                onClear = onClear,
            )

            DiagnosticsEventList(
                events = visibleEvents,
                filter = filter,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 320.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LomoTextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.ok))
                }
                LomoTextButton(onClick = onCopyReport) {
                    Text(
                        stringResource(
                            if (copiedFeedback) {
                                R.string.settings_engine_diagnostics_copied
                            } else {
                                R.string.settings_engine_diagnostics_copy
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsEnvironmentCard(environment: DiagnosticsEnvironmentSnapshot) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(AppSpacing.MediumSmall),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text =
                    stringResource(
                        R.string.settings_engine_diagnostics_env_header,
                        environment.appVersion,
                        environment.androidRelease,
                        environment.androidSdk,
                        environment.device,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                    stringResource(
                        R.string.settings_engine_diagnostics_env_meta,
                        environment.timeZoneId,
                        environment.timeZoneOffset,
                        environment.workspaceKind,
                        environment.engineReadiness,
                        environment.projectionFreshness,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val recovery = environment.recoveryFailure
                if (recovery != null) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Text(
                        text =
                        buildString {
                            append("${recovery.code} · ${recovery.category.wireValue}")
                            recovery.workspaceStage?.let { append(" · ${it.wireValue}") }
                            append(" · retry=${recovery.retryDisposition.wireValue}")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    failureExplanation(recovery.code)?.let { explanation ->
                        Text(
                            text = explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    sanitizeDiagnosticText(recovery.diagnostic)
                        .takeIf(String::isNotEmpty)
                        ?.let { scrubbed ->
                            Text(
                                text = scrubbed,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsFilterBar(
    filter: DiagnosticsFilter,
    errorCount: Int,
    totalCount: Int,
    onSelectFilter: (DiagnosticsFilter) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        FilterChip(
            selected = filter == DiagnosticsFilter.ERRORS_ONLY,
            onClick = { onSelectFilter(DiagnosticsFilter.ERRORS_ONLY) },
            label = {
                Text(stringResource(R.string.settings_engine_diagnostics_filter_errors, errorCount))
            },
        )
        FilterChip(
            selected = filter == DiagnosticsFilter.ALL,
            onClick = { onSelectFilter(DiagnosticsFilter.ALL) },
            label = {
                Text(stringResource(R.string.settings_engine_diagnostics_filter_all, totalCount))
            },
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        if (totalCount > 0) {
            LomoTextButton(onClick = onClear) {
                Text(stringResource(R.string.settings_engine_diagnostics_clear))
            }
        }
    }
}

@Composable
private fun DiagnosticsEventList(
    events: List<EngineDiagnosticEvent>,
    filter: DiagnosticsFilter,
    modifier: Modifier = Modifier,
) {
    if (events.isEmpty()) {
        val emptyMessage =
            if (filter == DiagnosticsFilter.ERRORS_ONLY) {
                R.string.settings_engine_diagnostics_no_errors
            } else {
                R.string.settings_engine_diagnostics_empty
            }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.Medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Text(
                text = stringResource(emptyMessage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(AppSpacing.Medium),
            )
        }
        return
    }
    SelectionContainer {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            items(events) { event ->
                DiagnosticsEventCard(event = event)
            }
        }
    }
}

@Composable
private fun DiagnosticsEventCard(event: EngineDiagnosticEvent) {
    val containerColor =
        if (event.isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    val extraLines = remember(event) { event.redactedExtraLines() }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Medium,
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.MediumSmall),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val timestamp =
                event.recordedAtMillis
                    .takeIf { it > 0L }
                    ?.let(::formatDiagnosticTimestamp)
            val headline =
                if (timestamp == null) {
                    event.summary()
                } else {
                    "[$timestamp] ${event.summary()}"
                }
            Text(
                text = headline,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (event.isError) FontWeight.SemiBold else FontWeight.Normal,
                color =
                if (event.isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            extraLines.forEach { extraLine ->
                Text(
                    text = extraLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun recentDiagnosticsReport(
    events: List<EngineDiagnosticEvent>,
    environment: DiagnosticsEnvironmentSnapshot,
): String = buildString {
    appendLine("lomo_diagnostics_v2")
    appendLine("[environment]")
    appendLine("app_version=${environment.appVersion}")
    appendLine("android_sdk=${environment.androidSdk}")
    appendLine("android_release=${environment.androidRelease}")
    appendLine("device=${environment.device}")
    appendLine("abis=${environment.abis}")
    appendLine("locale=${environment.locale}")
    appendLine("time_zone=${environment.timeZoneId}")
    appendLine("time_zone_offset=${environment.timeZoneOffset}")
    appendLine("[workspace]")
    appendLine("workspace_kind=${environment.workspaceKind}")
    appendLine("image_storage=${environment.imageStorageKind}")
    appendLine("voice_storage=${environment.voiceStorageKind}")
    appendLine("filename_format=${environment.filenameFormat}")
    appendLine("timestamp_format=${environment.timestampFormat}")
    appendLine("engine_readiness=${environment.engineReadiness}")
    appendLine("projection_freshness=${environment.projectionFreshness}")
    val recovery = environment.recoveryFailure
    if (recovery != null) {
        appendLine("recovery_code=${recovery.code}")
        appendLine("recovery_category=${recovery.category.wireValue}")
        appendLine("recovery_retry=${recovery.retryDisposition.wireValue}")
        recovery.workspaceStage?.let { appendLine("recovery_stage=${it.wireValue}") }
        sanitizeDiagnosticText(recovery.diagnostic)
            .takeIf(String::isNotEmpty)
            ?.let { appendLine("recovery_detail=$it") }
    }
    val errorCount = events.count { it.isError }
    appendLine("[events errors=$errorCount total=${events.size}]")
    events.forEach { event ->
        val timestamp = event.recordedAtMillis.takeIf { it > 0L }?.let(::formatDiagnosticTimestamp)
        if (timestamp == null) {
            appendLine(event.summary())
        } else {
            appendLine("[$timestamp] ${event.summary()}")
        }
        event.redactedExtraLines().forEach { extra ->
            appendLine("  $extra")
        }
    }
}

private fun buildDiagnosticsEnvironmentSnapshot(
    appVersion: String,
    mount: WorkspaceMount,
    filenameFormat: String,
    timestampFormat: String,
): DiagnosticsEnvironmentSnapshot {
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val offset = zone.rules.getOffset(now).id
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    val deviceName =
        when {
            manufacturer.isEmpty() -> model.ifEmpty { "unknown" }
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    val readinessLabel =
        when (mount.readiness) {
            EngineReadiness.AwaitingWorkspaceSelection -> "awaiting_selection"
            EngineReadiness.Opening -> "opening"
            EngineReadiness.Ready -> "ready"
            is EngineReadiness.ReadOnlyRecovery -> "read_only_recovery"
            EngineReadiness.ShuttingDown -> "shutting_down"
        }
    val freshnessLabel =
        when (val freshness = mount.freshness) {
            ProjectionFreshness.Unavailable -> "unavailable"
            is ProjectionFreshness.Retained -> "retained(rev=${freshness.lastVerifiedRevision})"
            is ProjectionFreshness.Revalidating -> "revalidating(rev=${freshness.lastVerifiedRevision})"
            is ProjectionFreshness.Verified -> "verified(rev=${freshness.revision})"
        }
    return DiagnosticsEnvironmentSnapshot(
        appVersion = appVersion,
        androidSdk = Build.VERSION.SDK_INT,
        androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
        device = deviceName,
        abis = Build.SUPPORTED_ABIS.joinToString(",").ifBlank { "unknown" },
        locale = Locale.getDefault().toLanguageTag(),
        timeZoneId = zone.id,
        timeZoneOffset = offset,
        workspaceKind = mount.location.storageKindLabel(absentLabel = "none"),
        imageStorageKind = mount.attachments.images.storageKindLabel(absentLabel = "follows_notes"),
        voiceStorageKind = mount.attachments.recordings.storageKindLabel(absentLabel = "follows_notes"),
        filenameFormat = filenameFormat,
        timestampFormat = timestampFormat,
        engineReadiness = readinessLabel,
        projectionFreshness = freshnessLabel,
        recoveryFailure = mount.readiness as? EngineReadiness.ReadOnlyRecovery,
    )
}

private fun StorageLocation?.storageKindLabel(absentLabel: String): String = when {
    this == null -> absentLabel
    raw.startsWith("content:", ignoreCase = true) -> "saf"
    else -> "direct"
}

private val DIAGNOSTIC_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private fun formatDiagnosticTimestamp(epochMillis: Long): String =
    DIAGNOSTIC_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis))

private const val DIALOG_WIDTH_FRACTION = 0.94f
private const val COPIED_FEEDBACK_MILLIS = 2_000L
