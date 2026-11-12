package com.lomo.app.feature.synccenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

@Composable
internal fun BinaryConflictDetail(facts: RemoteSyncBinaryConflictFacts) {
    Surface(
        shape = AppShapes.ExtraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier =
        Modifier
            .fillMaxWidth()
            .testTag("sync_center_binary_detail"),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            Text(
                text = stringResource(R.string.sync_center_binary_no_preview),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BinaryConflictMetadata(facts)
            Surface(
                shape = AppShapes.Medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text =
                    stringResource(
                        R.string.sync_center_digest_local,
                        facts.localDigest ?: stringResource(R.string.sync_center_value_unknown),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier =
                    Modifier
                        .padding(horizontal = AppSpacing.Small, vertical = 8.dp)
                        .testTag("sync_center_binary_local_digest"),
                )
            }
            Surface(
                shape = AppShapes.Medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text =
                    stringResource(
                        R.string.sync_center_digest_remote,
                        facts.remoteDigest ?: stringResource(R.string.sync_center_value_unknown),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = 8.dp),
                )
            }
            Surface(
                shape = AppShapes.Medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text =
                    stringResource(
                        R.string.sync_center_binary_source,
                        facts.sourceLabel,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = 8.dp),
                )
            }
        }
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
        verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
    ) {
        Surface(
            shape = AppShapes.ExtraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
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
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text =
                    stringResource(
                        R.string.sync_center_digest_local,
                        facts.localDigest ?: stringResource(R.string.sync_center_value_unknown),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text =
                    stringResource(
                        R.string.sync_center_digest_remote,
                        facts.remoteDigest ?: stringResource(R.string.sync_center_value_unknown),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
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
            shape = AppShapes.Large,
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
internal fun MarkdownBodySection(title: String, body: String?) {
    Surface(
        shape = AppShapes.Large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
            if (body == null) {
                Text(
                    text = stringResource(R.string.sync_center_markdown_body_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Surface(
                    shape = AppShapes.Medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier =
                        Modifier
                            .padding(AppSpacing.Small)
                            .testTag("sync_center_markdown_body"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val colorScheme = MaterialTheme.colorScheme
    val unselectedColors =
        ListItemDefaults.segmentedColors(
            containerColor = colorScheme.surfaceContainerLow,
            contentColor = colorScheme.onSurface,
            leadingContentColor = colorScheme.onSurfaceVariant,
        )
    val selectedColors =
        ListItemDefaults.segmentedColors(
            containerColor = colorScheme.secondaryContainer,
            contentColor = colorScheme.onSecondaryContainer,
            leadingContentColor = colorScheme.onSecondaryContainer,
        )
    // Segmented single-choice rows keep long resolution labels readable, unlike a chip row.
    val labels = kinds.map { kind -> resolutionKindLabel(kind) }
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        kinds.forEachIndexed { index, kind ->
            val selected = selectedKind == kind
            val baseShape = ListItemDefaults.segmentedShapes(index, kinds.size).shape
            val staticShapes =
                ListItemDefaults.shapes(
                    shape = baseShape,
                    selectedShape = baseShape,
                    pressedShape = baseShape,
                    focusedShape = baseShape,
                    hoveredShape = baseShape,
                    draggedShape = baseShape,
                )
            SegmentedListItem(
                selected = selected,
                onClick = { onSelect(kind) },
                shapes = staticShapes,
                colors = if (selected) selectedColors else unselectedColors,
                enabled = enabled,
                modifier =
                Modifier
                    .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                    .testTag("sync_center_resolution_${path}_$kind"),
                leadingContent = {
                    Icon(
                        imageVector =
                        when (kind) {
                            RemoteSyncConflictResolution.KIND_KEEP_LOCAL -> Icons.Outlined.Description

                            RemoteSyncConflictResolution.KIND_KEEP_REMOTE -> Icons.Outlined.CloudDownload

                            RemoteSyncConflictResolution.KIND_MERGED_BODY ->
                                Icons.AutoMirrored.Outlined.CompareArrows

                            else -> Icons.Outlined.Schedule
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingContent = {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        enabled = enabled,
                    )
                },
            ) {
                Text(
                    text = labels[index],
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun resolutionKindLabel(kind: String): String = when (kind) {
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

@Composable
private fun BinaryConflictMetadata(facts: RemoteSyncBinaryConflictFacts) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        Surface(
            shape = AppShapes.Medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text =
                stringResource(
                    R.string.sync_center_binary_mime,
                    facts.mimeType ?: stringResource(R.string.sync_center_value_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = 8.dp),
            )
        }
        Surface(
            shape = AppShapes.Medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text =
                stringResource(
                    R.string.sync_center_binary_size,
                    facts.sizeBytes?.toString()
                        ?: stringResource(R.string.sync_center_value_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = 8.dp),
            )
        }
    }
}
