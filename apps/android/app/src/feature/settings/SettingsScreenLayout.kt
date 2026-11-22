package com.lomo.app.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.domain.model.StorageArea
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroup
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenScaffold(
    onBackClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    uiState: SettingsScreenUiState,
    dialogState: SettingsDialogState,
    features: SettingsFeatures,
    resources: SettingsResources,
    storagePickers: StoragePickerActions,
    currentVersion: String,
    manualUpdateState: SettingsManualUpdateState,
    onOpenAvailableUpdateDialog: (AppUpdateDialogState) -> Unit,
) {
    val motionScheme = MaterialTheme.motionScheme
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    AnimatedContent(
        targetState = resources.currentLanguageTag,
        transitionSpec = settingsLanguageTransitionSpec(motionScheme),
        label = "SettingsLanguageTransition",
    ) { languageTag ->
        key(languageTag) {
            Scaffold(
                modifier =
                Modifier
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .benchmarkAnchorRoot(BenchmarkAnchorContract.SETTINGS_ROOT),
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    SettingsTopBar(
                        onBackClick = onBackClick,
                        scrollBehavior = scrollBehavior,
                    )
                },
            ) { padding ->
                SettingsBody(
                    padding = padding,
                    uiState = uiState,
                    dialogState = dialogState,
                    features = features,
                    resources = resources,
                    storagePickers = storagePickers,
                    currentVersion = currentVersion,
                    manualUpdateState = manualUpdateState,
                    onOpenAvailableUpdateDialog = onOpenAvailableUpdateDialog,
                )
            }
        }
    }
}

private fun settingsLanguageTransitionSpec(
    motionScheme: MotionScheme,
): AnimatedContentTransitionScope<String>.() -> ContentTransform = {
    (
        fadeIn(
            animationSpec =
            motionScheme.defaultEffectsSpec(),
        ) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec =
                motionScheme.defaultSpatialSpec(),
            )
        ).togetherWith(
        fadeOut(
            animationSpec =
            motionScheme.defaultEffectsSpec(),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBackClick: () -> Unit, scrollBehavior: TopAppBarScrollBehavior) {
    val haptic = LocalAppHapticFeedback.current
    LargeTopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
        navigationIcon = {
            LomoIconButton(
                onClick = {
                    haptic.medium()
                    onBackClick()
                },
                modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.SETTINGS_BACK_BUTTON),
            ) {
                LomoIcon(
                    icon = LomoIcons.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        colors =
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun SettingsBody(
    padding: PaddingValues,
    uiState: SettingsScreenUiState,
    dialogState: SettingsDialogState,
    features: SettingsFeatures,
    resources: SettingsResources,
    storagePickers: StoragePickerActions,
    currentVersion: String,
    manualUpdateState: SettingsManualUpdateState,
    onOpenAvailableUpdateDialog: (AppUpdateDialogState) -> Unit,
) {
    val heroState =
        remember(uiState.git, uiState.webDav, uiState.s3) {
            computeSettingsHomeHeroState(
                git =
                HomeHeroProviderFacts(
                    enabled = uiState.git.providerSettings.enabled,
                    lastSync = uiState.git.providerSettings.lastSyncTime,
                    syncState = uiState.git.providerSettings.syncState,
                ),
                webDav =
                HomeHeroProviderFacts(
                    enabled = uiState.webDav.providerSettings.enabled,
                    lastSync = uiState.webDav.providerSettings.lastSyncTime,
                    syncState = uiState.webDav.providerSettings.syncState,
                ),
                s3 =
                HomeHeroProviderFacts(
                    enabled = uiState.s3.providerSettings.enabled,
                    lastSync = uiState.s3.providerSettings.lastSyncTime,
                    syncState = uiState.s3.providerSettings.syncState,
                ),
            )
        }
    val notSetLabel = stringResource(R.string.settings_not_set)
    val languageLabel =
        resources.dialogOptions.languageLabels[resources.currentLanguageTag]
            ?: resources.currentLanguageTag
    val themeLabel =
        resolveThemeModeSummaryLabel(
            themeMode = uiState.display.themeMode,
            pureBlackDark = uiState.display.pureBlackDark,
            themeModeLabels = resources.dialogOptions.themeModeLabels,
            pureBlackSystemLabel = stringResource(R.string.settings_theme_system_pure_black),
            pureBlackDarkLabel = stringResource(R.string.settings_theme_dark_pure_black),
        )
    val colorPaletteLabel = colorSourceSummaryLabel(uiState.display.colorSource)
    val fontLabel = fontPreferenceSummaryLabel(uiState.display)

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = AppSpacing.ScreenHorizontalPadding,
                vertical = AppSpacing.MediumSmall,
            ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        SettingsHomeHero(
            heroState = heroState,
            onSyncNow = { triggerEnabledProviderSyncs(features, uiState) },
        )

        SettingsStorageSection(
            storage = features.storage,
            directories = uiState.storage,
            storagePickers = storagePickers,
            notSetLabel = notSetLabel,
        )

        SettingsPreferencesQuickGroup(
            themeLabel = themeLabel,
            languageLabel = languageLabel,
            colorPaletteLabel = colorPaletteLabel,
            fontLabel = fontLabel,
            onThemeClick = { dialogState.showThemeDialog = true },
            onLanguageClick = { dialogState.showLanguageDialog = true },
            onTypographyClick = { dialogState.activeSubPage = SettingsSubPage.TYPOGRAPHY },
            onColorPaletteClick = { dialogState.activeSubPage = SettingsSubPage.COLOR_PALETTE },
            onFontClick = { dialogState.activeSubPage = SettingsSubPage.FONT },
        )

        SettingsCategoryEntriesGroup(dialogState = dialogState)

        SettingsHomeFooter(
            currentVersion = currentVersion,
            manualUpdateState = manualUpdateState,
            onCheckUpdates = {
                val update = manualUpdateState
                if (update is SettingsManualUpdateState.UpdateAvailable) {
                    onOpenAvailableUpdateDialog(update.dialogState)
                } else {
                    features.system.checkForUpdatesManually()
                }
            },
        )
    }
}

@Composable
private fun SettingsCategoryEntriesGroup(dialogState: SettingsDialogState) {
    SettingsGroup(title = stringResource(R.string.settings_home_more_section_title)) {
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_category_sync_backup),
                subtitle = stringResource(R.string.settings_category_sync_backup_subtitle),
                modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.SETTINGS_SYNC_BACKUP),
                icon = LomoIcons.Sync,
                onClick = { dialogState.activeSubPage = SettingsSubPage.SYNC_BACKUP },
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_category_storage_formats),
                subtitle = stringResource(R.string.settings_category_storage_formats_subtitle),
                icon = LomoIcons.FolderOpen,
                onClick = { dialogState.activeSubPage = SettingsSubPage.STORAGE_FORMATS },
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_category_interaction_security),
                subtitle = stringResource(R.string.settings_category_interaction_security_subtitle),
                modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.SETTINGS_INTERACTION_SECURITY),
                icon = LomoIcons.Vibration,
                onClick = { dialogState.activeSubPage = SettingsSubPage.INTERACTION_SECURITY },
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_category_about),
                subtitle = stringResource(R.string.settings_category_about_subtitle),
                icon = LomoIcons.Info,
                onClick = { dialogState.activeSubPage = SettingsSubPage.ABOUT },
            )
        }
    }
}

@Composable
private fun SettingsHomeFooter(
    currentVersion: String,
    manualUpdateState: SettingsManualUpdateState,
    onCheckUpdates: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    val versionLabel =
        if (currentVersion.isNotBlank()) {
            stringResource(R.string.settings_home_footer_version_label, currentVersion)
        } else {
            stringResource(R.string.settings_current_version_unknown)
        }
    val checking = manualUpdateState is SettingsManualUpdateState.Checking
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !checking) {
                haptic.medium()
                onCheckUpdates()
            }
            .padding(
                horizontal = AppSpacing.ScreenHorizontalPadding,
                vertical = AppSpacing.Medium,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = versionLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(AppSpacing.Small))
        Text(
            text = "·",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.width(AppSpacing.Small))
        Text(
            text = stringResource(R.string.settings_home_footer_check_updates),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun triggerEnabledProviderSyncs(features: SettingsFeatures, uiState: SettingsScreenUiState) {
    if (uiState.git.providerSettings.enabled) features.git.provider.triggerSyncNow()
    if (uiState.webDav.providerSettings.enabled) features.webDav.provider.triggerSyncNow()
    if (uiState.s3.providerSettings.enabled) features.s3.provider.triggerSyncNow()
}

@Composable
private fun SettingsStorageSection(
    storage: SettingsStorageFeatureViewModel,
    directories: StorageSectionState,
    storagePickers: StoragePickerActions,
    notSetLabel: String,
) {
    val rootOperationState by storage.rootOperationState.collectAsStateWithLifecycle()
    val memoDirectoryLabel =
        when (rootOperationState) {
            WorkspaceRootOperationState.Idle -> directories.rootDirectory.subtitle(notSetLabel)

            WorkspaceRootOperationState.Switching ->
                stringResource(R.string.settings_storage_switching_workspace)

            WorkspaceRootOperationState.Indexing ->
                stringResource(R.string.settings_storage_scanning_workspace)

            is WorkspaceRootOperationState.Failed ->
                stringResource(R.string.settings_storage_index_failed)
        }
    val pendingMediaAreas by storage.pendingMediaAreas.collectAsStateWithLifecycle()
    val applyingDirectoryLabel = stringResource(R.string.settings_storage_applying_directory)
    val attachmentDefaultLabel = when ((directories.rootDirectory as? DirectoryDisplayState.Resolved)?.value) {
        null -> notSetLabel
        else -> stringResource(R.string.settings_storage_follows_notes_folder)
    }

    SettingsStorageQuickGroup(
        memoDirectoryLabel = memoDirectoryLabel,
        imageDirectoryLabel =
        if (StorageArea.IMAGE in pendingMediaAreas) {
            applyingDirectoryLabel
        } else {
            directories.imageDirectory.subtitle(attachmentDefaultLabel)
        },
        voiceDirectoryLabel =
        if (StorageArea.VOICE in pendingMediaAreas) {
            applyingDirectoryLabel
        } else {
            directories.voiceDirectory.subtitle(attachmentDefaultLabel)
        },
        onMemoDirectoryClick = storagePickers.openRoot,
        onImageDirectoryClick = storagePickers.openImage,
        onVoiceDirectoryClick = storagePickers.openVoice,
    )
}
