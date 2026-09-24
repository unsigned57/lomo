package com.lomo.app.feature.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontFamily
import com.lomo.app.R
import com.lomo.domain.model.CustomFontImportResult
import com.lomo.domain.model.CustomFontInfo
import com.lomo.domain.model.CustomFontNameState
import com.lomo.domain.model.CustomFontRejection
import com.lomo.domain.model.CustomFontSource
import com.lomo.domain.model.FontPreference
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FontSettingsPage(
    uiState: DisplaySectionState,
    displayFeature: SettingsDisplayFeatureViewModel,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptic = LocalAppHapticFeedback.current
    val context = LocalContext.current

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) handleFontImport(context, uri, displayFeature)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { FontTopAppBar(onBack = { haptic.medium(); onBack() }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = AppSpacing.ScreenHorizontalPadding,
                    vertical = AppSpacing.MediumSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            FontPreviewCard()
            SystemFontCard(
                selected = uiState.fontPreference is FontPreference.SystemDefault,
                onSelect = {
                    haptic.medium()
                    displayFeature.updateFontPreference(FontPreference.SystemDefault)
                },
            )
            ImportFontButton(onClick = {
                haptic.medium()
                pickerLauncher.launch(arrayOf("*/*"))
            })
            if (uiState.availableCustomFonts.isEmpty()) {
                CustomFontEmptyState()
            } else {
                Text(
                    text = stringResource(R.string.settings_font_imported_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                uiState.availableCustomFonts.forEach { info ->
                    val selected =
                        (uiState.fontPreference as? FontPreference.UserImported)?.id == info.id
                    CustomFontRow(
                        info = info,
                        selected = selected,
                        previewFontFamily = displayFeature.previewFontFamily,
                        onSelect = {
                            haptic.medium()
                            displayFeature.updateFontPreference(FontPreference.UserImported(info.id))
                        },
                        onDelete = {
                            displayFeature.deleteCustomFont(info.id) { deletion ->
                                if (deletion == CustomFontDeletion.SELECTION_RESET) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_font_selected_deleted),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontTopAppBar(
    onBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    LargeTopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.FontDownload,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(AppSpacing.Medium))
                Text(stringResource(R.string.settings_font))
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun CustomFontEmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.FontDownload,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.settings_font_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.settings_font_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun FontPreviewCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_font_preview_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_font_preview_sample),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.settings_font_preview_sample),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_font_preview_alphabet_sample),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SystemFontCard(
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onSelect),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_font_preview_glyph_sample),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_font_system_default),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = stringResource(R.string.settings_font_system_default_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ImportFontButton(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Outlined.UploadFile,
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.settings_font_import_action))
    }
}

@Composable
private fun CustomFontRow(
    info: CustomFontInfo,
    selected: Boolean,
    previewFontFamily: suspend (String) -> FontFamily,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    // Preview resolves through the same CustomFontHost as the app theme — off the composition
    // path and keyed by content identity — so preview and theme never disagree on a version.
    val customFontFamily by produceState<FontFamily>(FontFamily.SansSerif, info.id) {
        value = previewFontFamily(info.id)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onSelect),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = info.displayName.take(2).uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = customFontFamily,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontFamily = customFontFamily,
                )
                if (info.nameState == CustomFontNameState.LEGACY_IMPORT) {
                    Text(
                        text = stringResource(R.string.settings_font_legacy_name_state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    text = formatBytes(info.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.settings_font_delete),
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private const val KIB: Long = 1024L
private const val MIB: Long = KIB * 1024L

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= MIB -> "%.1f MB".format(Locale.US, bytes.toDouble() / MIB)
        bytes >= KIB -> "%.1f KB".format(Locale.US, bytes.toDouble() / KIB)
        else -> "$bytes B"
    }

private fun handleFontImport(
    context: Context,
    uri: Uri,
    displayFeature: SettingsDisplayFeatureViewModel,
) {
    val resolver = context.contentResolver
    val originalName = resolveDisplayName(resolver, uri) ?: "imported.ttf"

    val extension = originalName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension != "ttf" && extension != "otf") {
        Toast.makeText(
            context,
            context.getString(R.string.settings_font_import_invalid_type),
            Toast.LENGTH_SHORT
        ).show()
        return
    }

    // The byte budget and font validation live in the store; the URI is passed as a stream
    // source so the file is never materialized into memory or onto disk before validation.
    val source = CustomFontSource { resolver.openInputStream(uri) }
    displayFeature.importCustomFont(source, originalName) { result ->
        val message =
            when (result) {
                is CustomFontImportResult.Imported -> R.string.settings_font_import_success
                is CustomFontImportResult.Rejected ->
                    when (result.reason) {
                        CustomFontRejection.UNSUPPORTED_TYPE ->
                            R.string.settings_font_import_invalid_type
                        CustomFontRejection.OVERSIZED ->
                            R.string.settings_font_import_too_large
                        CustomFontRejection.INVALID_FONT ->
                            R.string.settings_font_import_invalid_font
                        CustomFontRejection.UNREADABLE ->
                            R.string.settings_font_import_failed
                    }
            }
        Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
    }
}

private fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String? {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                // The store owns decoding and sanitization; the UI passes the provider name raw.
                return cursor.getString(index)
            }
        }
    }
    return null
}
