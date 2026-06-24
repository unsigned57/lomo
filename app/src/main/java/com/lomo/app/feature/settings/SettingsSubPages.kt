package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.toImmutableMap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncBackupSettingsPage(
    uiState: SettingsScreenUiState,
    dialogState: SettingsDialogState,
    features: SettingsFeatures,
    dialogOptions: SettingsDialogOptions,
    storagePickers: StoragePickerActions,
    migrationPickers: MigrationPickerActions,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptic = LocalAppHapticFeedback.current
    val subPageMigrationPickers = rememberMigrationPickersForSubPages(migrationPickers = migrationPickers)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Medium))
                        Text(stringResource(R.string.settings_category_sync_backup))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.medium()
                        onBack()
                    }) {
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
    ) { padding ->
        SyncBackupSettingsBody(
            padding = padding,
            uiState = uiState,
            dialogState = dialogState,
            features = features,
            dialogOptions = dialogOptions,
            storagePickers = storagePickers,
            migrationPickers = subPageMigrationPickers,
        )
    }
}

@Composable
private fun SyncBackupSettingsBody(
    padding: PaddingValues,
    uiState: SettingsScreenUiState,
    dialogState: SettingsDialogState,
    features: SettingsFeatures,
    dialogOptions: SettingsDialogOptions,
    storagePickers: StoragePickerActions,
    migrationPickers: MigrationPickers,
) {
    val gitSyncIntervalLabels = remember(dialogOptions.gitSyncIntervalLabels) {
        dialogOptions.gitSyncIntervalLabels.toImmutableMap()
    }
    val webDavProviderLabels = remember(dialogOptions.webDavProviderLabels) {
        dialogOptions.webDavProviderLabels.toImmutableMap()
    }
    val s3SyncLabelSources =
        rememberS3SyncLabelSources(
            options = dialogOptions,
            syncIntervalLabels = gitSyncIntervalLabels,
        )
    val migrationOperationState by features.migration.operationState.collectAsStateWithLifecycle()

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
        // LAN Share
        LanShareSettingsSectionContainer(
            state = uiState.lanShare,
            dialogState = dialogState,
            lanShareFeature = features.lanShare,
        )

        // Snapshots
        SnapshotSettingsSection(
            state = uiState.snapshot,
            onToggleMemoSnapshots = { enabled ->
                if (enabled) {
                    features.snapshot.updateMemoSnapshotsEnabled(true)
                } else {
                    dialogState.pendingSnapshotDisableTarget = SettingsSnapshotDisableTarget.MEMO
                }
            },
            onOpenMemoCountDialog = { dialogState.showMemoSnapshotCountDialog = true },
            onOpenMemoAgeDialog = { dialogState.showMemoSnapshotAgeDialog = true },
        )

        // Migration (Export/Import)
        MigrationSettingsSection(
            operationState = migrationOperationState,
            onExportNotesArchive = migrationPickers.onExportNotesArchive,
            onImportNotesArchive = migrationPickers.onImportNotesArchive,
            onOpenExportSettingsPasswordDialog = {
                dialogState.migrationPasswordInput = ""
                dialogState.showMigrationExportSettingsPasswordDialog = true
            },
            onOpenImportSettingsPasswordDialog = {
                dialogState.migrationPasswordInput = ""
                dialogState.showMigrationImportSettingsPasswordDialog = true
            },
        )

        // Sync Settings (Inbox, Git, WebDAV, S3)
        SyncSettingsSection(
            storageState = uiState.storage,
            onToggleSyncInbox = features.storage::updateSyncInboxEnabled,
            onSelectSyncInbox = storagePickers.openSyncInbox,
            gitContent = {
                GitSyncSettingsSectionContainer(
                    state = uiState.git,
                    dialogState = dialogState,
                    gitFeature = features.git,
                    gitSyncIntervalLabels = gitSyncIntervalLabels,
                )
            },
            webDavContent = {
                WebDavSyncSettingsSectionContainer(
                    state = uiState.webDav,
                    dialogState = dialogState,
                    webDavFeature = features.webDav,
                    gitSyncIntervalLabels = gitSyncIntervalLabels,
                    webDavProviderLabels = webDavProviderLabels,
                )
            },
            s3Content = {
                S3SyncSettingsSectionContainer(
                    state = uiState.s3,
                    dialogState = dialogState,
                    s3Feature = features.s3,
                    onSelectLocalSyncDirectory = storagePickers.openS3LocalSyncDirectory,
                    labelSources = s3SyncLabelSources,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageFormatsSettingsPage(
    uiState: SettingsScreenUiState,
    dialogState: SettingsDialogState,
    features: SettingsFeatures,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptic = LocalAppHapticFeedback.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Medium))
                        Text(stringResource(R.string.settings_category_storage_formats))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.medium()
                        onBack()
                    }) {
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
            FormatsSettingsSection(
                filenameFormat = uiState.storage.filenameFormat,
                timestampFormat = uiState.storage.timestampFormat,
                dateFormat = uiState.display.dateFormat,
                timeFormat = uiState.display.timeFormat,
                onOpenFilenameFormatDialog = { dialogState.showFilenameDialog = true },
                onOpenTimestampFormatDialog = { dialogState.showTimestampDialog = true },
                onOpenDateFormatDialog = { dialogState.showDateDialog = true },
                onOpenTimeFormatDialog = { dialogState.showTimeDialog = true },
            )

            ShareCardSettingsSection(
                state = uiState.shareCard,
                onToggleShowTime = features.shareCard::updateShareCardShowTime,
                onToggleShowBrand = features.shareCard::updateShareCardShowBrand,
                onOpenSignatureDialog = {
                    dialogState.shareCardSignatureInput = uiState.shareCard.signatureText
                    dialogState.showShareCardSignatureDialog = true
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InteractionSecuritySettingsPage(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptic = LocalAppHapticFeedback.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Vibration,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Medium))
                        Text(stringResource(R.string.settings_category_interaction_security))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.medium()
                        onBack()
                    }) {
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
            InteractionSettingsSection(
                state = uiState.interaction,
                onToggleInputHints = features.interaction::updateShowInputHints,
                onToggleDoubleTapEdit = features.interaction::updateDoubleTapEditEnabled,
                onToggleFreeTextCopy = features.interaction::updateFreeTextCopyEnabled,
                onToggleMemoActionAutoReorder = features.interaction::updateMemoActionAutoReorderEnabled,
                onToggleQuickSaveOnBack = features.interaction::updateQuickSaveOnBackEnabled,
                onToggleScrollbar = features.interaction::updateScrollbarEnabled,
            )
        }
    }
}

data class MigrationPickers(
    val onExportNotesArchive: () -> Unit,
    val onImportNotesArchive: () -> Unit,
)

@Composable
private fun rememberMigrationPickersForSubPages(migrationPickers: MigrationPickerActions): MigrationPickers =
    remember(migrationPickers) {
        MigrationPickers(
            onExportNotesArchive = migrationPickers.exportNotesArchive,
            onImportNotesArchive = migrationPickers.importNotesArchive,
        )
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSettingsPage(
    uiState: SettingsScreenUiState,
    aboutState: AboutSectionState,
    features: SettingsFeatures,
    onOpenAvailableUpdateDialog: (AppUpdateDialogState) -> Unit,
    onPreviewDebugUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptic = LocalAppHapticFeedback.current
    val uriHandler = LocalUriHandler.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Medium))
                        Text(stringResource(R.string.settings_category_about))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.medium()
                        onBack()
                    }) {
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
            AboutSettingsSection(
                state = aboutState,
                systemState = uiState.system,
                onCheckUpdates = {
                    val updateState = aboutState.manualUpdateState
                    if (updateState is SettingsManualUpdateState.UpdateAvailable) {
                        onOpenAvailableUpdateDialog(updateState.dialogState)
                    } else {
                        features.system.checkForUpdatesManually()
                    }
                },
                onToggleCheckUpdatesOnStartup = features.system::updateCheckUpdatesOnStartup,
                onPreviewDebugUpdate = onPreviewDebugUpdate,
                onOpenGithub = { uriHandler.openUri(GITHUB_URL) },
            )
        }
    }
}
