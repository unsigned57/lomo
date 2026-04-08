package com.lomo.app.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.toImmutableMap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenScaffold(
    uiState: SettingsScreenUiState,
    aboutState: AboutSectionState,
    onBackClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    dialogState: SettingsDialogState,
    features: SettingsFeatures,
    resources: SettingsResources,
    storagePickers: StoragePickerActions,
    onOpenAvailableUpdateDialog: (AppUpdateDialogState) -> Unit,
    onPreviewDebugUpdate: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    AnimatedContent(
        targetState = resources.currentLanguageTag,
        transitionSpec = settingsLanguageTransitionSpec(),
        label = "SettingsLanguageTransition",
    ) { languageTag ->
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
                aboutState = aboutState,
                languageTag = languageTag,
                dialogState = dialogState,
                features = features,
                dialogOptions = resources.dialogOptions,
                storagePickers = storagePickers,
                onOpenAvailableUpdateDialog = onOpenAvailableUpdateDialog,
                onPreviewDebugUpdate = onPreviewDebugUpdate,
            )
        }
    }
}

private fun settingsLanguageTransitionSpec():
    AnimatedContentTransitionScope<String>.() -> ContentTransform = {
    (
        fadeIn(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationLong2,
                    easing = MotionTokens.EasingEmphasized,
                ),
        ) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                        easing = MotionTokens.EasingEmphasized,
                    ),
            )
    ).togetherWith(
        fadeOut(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationLong2,
                    easing = MotionTokens.EasingEmphasized,
                ),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(
    onBackClick: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val haptic = LocalAppHapticFeedback.current
    LargeTopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
        navigationIcon = {
            IconButton(
                onClick = {
                    haptic.medium()
                    onBackClick()
                },
                modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.SETTINGS_BACK_BUTTON),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
    aboutState: AboutSectionState,
    languageTag: String,
    dialogState: SettingsDialogState,
    features: SettingsFeatures,
    dialogOptions: SettingsDialogOptions,
    storagePickers: StoragePickerActions,
    onOpenAvailableUpdateDialog: (AppUpdateDialogState) -> Unit,
    onPreviewDebugUpdate: () -> Unit,
) {
    val gitSyncIntervalLabels = remember(dialogOptions.gitSyncIntervalLabels) {
        dialogOptions.gitSyncIntervalLabels.toImmutableMap()
    }
    val webDavProviderLabels = remember(dialogOptions.webDavProviderLabels) {
        dialogOptions.webDavProviderLabels.toImmutableMap()
    }
    val s3PathStyleLabels = remember(dialogOptions.s3PathStyleLabels) {
        dialogOptions.s3PathStyleLabels.toImmutableMap()
    }
    val s3EncryptionModeLabels =
        remember(dialogOptions.s3EncryptionModeLabels) { dialogOptions.s3EncryptionModeLabels.toImmutableMap() }
    val s3RcloneFilenameEncryptionLabels =
        remember(dialogOptions.s3RcloneFilenameEncryptionLabels) {
            dialogOptions.s3RcloneFilenameEncryptionLabels.toImmutableMap()
        }
    val s3RcloneFilenameEncodingLabels =
        remember(dialogOptions.s3RcloneFilenameEncodingLabels) {
            dialogOptions.s3RcloneFilenameEncodingLabels.toImmutableMap()
        }
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
        PrimarySettingsSections(
            uiState = uiState,
            languageTag = languageTag,
            dialogState = dialogState,
            storagePickers = storagePickers,
            features = features,
            dialogOptions = dialogOptions,
        )
        GitSyncSettingsSectionContainer(
            state = uiState.git,
            dialogState = dialogState,
            gitFeature = features.git,
            gitSyncIntervalLabels = gitSyncIntervalLabels,
        )
        WebDavSyncSettingsSectionContainer(
            state = uiState.webDav,
            dialogState = dialogState,
            webDavFeature = features.webDav,
            gitSyncIntervalLabels = gitSyncIntervalLabels,
            webDavProviderLabels = webDavProviderLabels,
        )
        S3SyncSettingsSectionContainer(
            state = uiState.s3,
            dialogState = dialogState,
            s3Feature = features.s3,
            onSelectLocalSyncDirectory = storagePickers.openS3LocalSyncDirectory,
            syncIntervalLabels = gitSyncIntervalLabels,
            pathStyleLabels = s3PathStyleLabels,
            encryptionModeLabels = s3EncryptionModeLabels,
            rcloneFilenameEncryptionLabels = s3RcloneFilenameEncryptionLabels,
            rcloneFilenameEncodingLabels = s3RcloneFilenameEncodingLabels,
        )
        FooterSettingsSections(
            uiState = uiState,
            aboutState = aboutState,
            dialogState = dialogState,
            interactionFeature = features.interaction,
            systemFeature = features.system,
            onOpenAvailableUpdateDialog = onOpenAvailableUpdateDialog,
            onPreviewDebugUpdate = onPreviewDebugUpdate,
        )
    }
}

@Composable
private fun PrimarySettingsSections(
    uiState: SettingsScreenUiState,
    languageTag: String,
    dialogState: SettingsDialogState,
    storagePickers: StoragePickerActions,
    features: SettingsFeatures,
    dialogOptions: SettingsDialogOptions,
) {
    StorageSettingsSection(
        state = uiState.storage,
        onSelectRoot = storagePickers.openRoot,
        onSelectImageRoot = storagePickers.openImage,
        onSelectVoiceRoot = storagePickers.openVoice,
        onOpenFilenameFormatDialog = { dialogState.showFilenameDialog = true },
        onOpenTimestampFormatDialog = { dialogState.showTimestampDialog = true },
    )
    DisplaySettingsSection(
        state = uiState.display,
        languageLabel = dialogOptions.languageLabels[languageTag] ?: languageTag,
        themeLabel =
            dialogOptions.themeModeLabels[uiState.display.themeMode] ?: uiState.display.themeMode.value,
        onOpenLanguageDialog = { dialogState.showLanguageDialog = true },
        onOpenThemeDialog = { dialogState.showThemeDialog = true },
        onOpenDateFormatDialog = { dialogState.showDateDialog = true },
        onOpenTimeFormatDialog = { dialogState.showTimeDialog = true },
    )
    LanShareSettingsSectionContainer(
        state = uiState.lanShare,
        dialogState = dialogState,
        lanShareFeature = features.lanShare,
    )
    ShareCardSettingsSection(
        state = uiState.shareCard,
        onToggleShowTime = features.shareCard::updateShareCardShowTime,
        onToggleShowBrand = features.shareCard::updateShareCardShowBrand,
    )
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
}

@Composable
private fun FooterSettingsSections(
    uiState: SettingsScreenUiState,
    aboutState: AboutSectionState,
    dialogState: SettingsDialogState,
    interactionFeature: SettingsInteractionFeatureViewModel,
    systemFeature: SettingsSystemFeatureViewModel,
    onOpenAvailableUpdateDialog: (AppUpdateDialogState) -> Unit,
    onPreviewDebugUpdate: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    InteractionSettingsSection(
        state = uiState.interaction,
        onToggleHaptic = interactionFeature::updateHapticFeedback,
        onToggleInputHints = interactionFeature::updateShowInputHints,
        onToggleDoubleTapEdit = interactionFeature::updateDoubleTapEditEnabled,
        onToggleFreeTextCopy = interactionFeature::updateFreeTextCopyEnabled,
        onToggleMemoActionAutoReorder = interactionFeature::updateMemoActionAutoReorderEnabled,
        onToggleAppLock = interactionFeature::updateAppLockEnabled,
        onToggleQuickSaveOnBack = interactionFeature::updateQuickSaveOnBackEnabled,
    )
    SystemSettingsSection(
        state = uiState.system,
        onToggleCheckUpdates = systemFeature::updateCheckUpdatesOnStartup,
    )
    AboutSettingsSection(
        state = aboutState,
        onCheckUpdates = {
            val updateState = aboutState.manualUpdateState
            if (updateState is SettingsManualUpdateState.UpdateAvailable) {
                onOpenAvailableUpdateDialog(updateState.dialogState)
            } else {
                systemFeature.checkForUpdatesManually()
            }
        },
        onPreviewDebugUpdate = onPreviewDebugUpdate,
        onOpenGithub = { uriHandler.openUri(GITHUB_URL) },
    )
}
