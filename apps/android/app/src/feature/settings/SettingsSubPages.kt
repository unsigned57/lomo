package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppSpacing
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
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenSyncCenter: (() -> Unit)? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val subPageMigrationPickers = rememberMigrationPickersForSubPages(migrationPickers = migrationPickers)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            SettingsPageTopAppBar(
                title = stringResource(R.string.settings_category_sync_backup),
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        SyncBackupSettingsBody(
            onOpenSyncCenter = onOpenSyncCenter,
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
    onOpenSyncCenter: (() -> Unit)? = null,
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
                start = AppSpacing.ScreenHorizontalPadding,
                end = AppSpacing.ScreenHorizontalPadding,
                top = AppSpacing.MediumSmall,
                bottom = AppSpacing.ExtraLarge,
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
            onOpenSyncCenter = onOpenSyncCenter,
            gitContent = {
                gitSyncSettingsSectionContainer(
                    state = uiState.git,
                    dialogState = dialogState,
                    gitFeature = features.git,
                    gitSyncIntervalLabels = gitSyncIntervalLabels,
                )
            },
            webDavContent = {
                webDavSyncSettingsSectionContainer(
                    state = uiState.webDav,
                    dialogState = dialogState,
                    webDavFeature = features.webDav,
                    gitSyncIntervalLabels = gitSyncIntervalLabels,
                    webDavProviderLabels = webDavProviderLabels,
                )
            },
            s3Content = {
                s3SyncSettingsSectionContainer(
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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsPageTopAppBar(
                title = stringResource(R.string.settings_category_storage_formats),
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = AppSpacing.ScreenHorizontalPadding,
                    end = AppSpacing.ScreenHorizontalPadding,
                    top = AppSpacing.MediumSmall,
                    bottom = AppSpacing.ExtraLarge,
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

            HeatmapSettingsSection(
                thresholds = uiState.display.calendarHeatmapThresholds,
                onOpenThresholdDialog = { dialogState.showCalendarHeatmapThresholdsDialog = true },
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
    onOpenQuickBall: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsPageTopAppBar(
                title = stringResource(R.string.settings_category_interaction_security),
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = AppSpacing.ScreenHorizontalPadding,
                    end = AppSpacing.ScreenHorizontalPadding,
                    top = AppSpacing.MediumSmall,
                    bottom = AppSpacing.ExtraLarge,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            com.lomo.ui.component.settings.SettingsGroup(title = stringResource(R.string.quick_ball_title)) {
                item {
                    com.lomo.ui.component.settings.PreferenceItem(
                        title = stringResource(R.string.quick_ball_title),
                        subtitle = stringResource(R.string.quick_ball_subtitle),
                        icon = LomoIcons.TouchApp,
                        onClick = onOpenQuickBall,
                        modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.SETTINGS_QUICK_BALL),
                    )
                }
            }
            InteractionSettingsSection(
                state = uiState.interaction,
                onToggleInputHints = features.interaction::updateShowInputHints,
                onToggleDoubleTapEdit = features.interaction::updateDoubleTapEditEnabled,
                onToggleFreeTextCopy = features.interaction::updateFreeTextCopyEnabled,
                onToggleQuickSaveOnBack = features.interaction::updateQuickSaveOnBackEnabled,
                onToggleScrollbar = features.interaction::updateScrollbarEnabled,
                onToggleAutoOpenInputOnForeground = features.interaction::updateAutoOpenInputOnForeground,
                onToggleAppLock = features.interaction::updateAppLockEnabled,
                onToggleHaptic = features.interaction::updateHapticFeedback,
            )
        }
    }
}

data class MigrationPickers(val onExportNotesArchive: () -> Unit, val onImportNotesArchive: () -> Unit)

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
    val uriHandler = LocalUriHandler.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsPageTopAppBar(
                title = stringResource(R.string.settings_category_about),
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = AppSpacing.ScreenHorizontalPadding,
                    end = AppSpacing.ScreenHorizontalPadding,
                    top = AppSpacing.MediumSmall,
                    bottom = AppSpacing.ExtraLarge,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            AboutSettingsSection(
                state = aboutState,
                systemState = uiState.system,
                filenameFormat = uiState.storage.filenameFormat,
                timestampFormat = uiState.storage.timestampFormat,
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
