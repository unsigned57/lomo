package com.lomo.app.feature.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.lanshare.LanSharePairingDialogTriggerPolicy
import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val storageFeature = viewModel.storageFeature
    val displayFeature = viewModel.displayFeature
    val shareCardFeature = viewModel.shareCardFeature
    val interactionFeature = viewModel.interactionFeature
    val systemFeature = viewModel.systemFeature
    val lanShareFeature = viewModel.lanShareFeature
    val gitFeature = viewModel.gitFeature

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val haptic = LocalAppHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unknownErrorMessage = stringResource(R.string.error_unknown)
    val gitConflictSummary = stringResource(R.string.settings_git_sync_conflict_summary)
    val gitDirectPathRequired = stringResource(R.string.settings_git_sync_direct_path_required)
    val dialogState = rememberSettingsDialogState()

    val dateFormats = listOf("yyyy-MM-dd", "MM/dd/yyyy", "dd/MM/yyyy", "yyyy/MM/dd")
    val timeFormats = listOf("HH:mm", "hh:mm a", "HH:mm:ss", "hh:mm:ss a")
    val themeModes = ThemeMode.entries
    val shareCardStyles = ShareCardStyle.entries
    val filenameFormats = StorageFilenameFormats.supportedPatterns
    val timestampFormats = StorageTimestampFormats.supportedPatterns
    val gitSyncIntervals = listOf("30min", "1h", "6h", "12h", "24h")

    val themeModeLabels =
        mapOf(
            ThemeMode.SYSTEM to stringResource(R.string.settings_system),
            ThemeMode.LIGHT to stringResource(R.string.settings_light_mode),
            ThemeMode.DARK to stringResource(R.string.settings_dark_mode),
        )
    val shareCardStyleLabels =
        mapOf(
            ShareCardStyle.WARM to stringResource(R.string.share_card_style_warm),
            ShareCardStyle.CLEAN to stringResource(R.string.share_card_style_clean),
            ShareCardStyle.DARK to stringResource(R.string.share_card_style_dark),
        )
    val languageLabels =
        mapOf(
            "system" to stringResource(R.string.settings_system),
            "en" to stringResource(R.string.settings_english),
            "zh-CN" to stringResource(R.string.settings_simplified_chinese),
            "zh-Hans-CN" to stringResource(R.string.settings_simplified_chinese),
        )
    val gitSyncIntervalLabels =
        mapOf(
            "30min" to stringResource(R.string.settings_git_sync_interval_30min),
            "1h" to stringResource(R.string.settings_git_sync_interval_1h),
            "6h" to stringResource(R.string.settings_git_sync_interval_6h),
            "12h" to stringResource(R.string.settings_git_sync_interval_12h),
            "24h" to stringResource(R.string.settings_git_sync_interval_24h),
        )

    val currentLocales = AppCompatDelegate.getApplicationLocales()
    val currentLanguageTag =
        if (!currentLocales.isEmpty) {
            currentLocales[0]?.toLanguageTag() ?: "system"
        } else {
            "system"
        }

    val rootLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching {
                    context.contentResolver.takePersistableUriPermission(it, flags)
                    storageFeature.updateRootUri(it.toString())
                }.onFailure { throwable ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            throwable.message?.takeIf { it.isNotBlank() } ?: unknownErrorMessage,
                        )
                    }
                }
            }
        }

    val imageLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching {
                    context.contentResolver.takePersistableUriPermission(it, flags)
                    storageFeature.updateImageUri(it.toString())
                }.onFailure { throwable ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            throwable.message?.takeIf { it.isNotBlank() } ?: unknownErrorMessage,
                        )
                    }
                }
            }
        }

    val voiceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching {
                    context.contentResolver.takePersistableUriPermission(it, flags)
                    storageFeature.updateVoiceUri(it.toString())
                }.onFailure { throwable ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            throwable.message?.takeIf { it.isNotBlank() } ?: unknownErrorMessage,
                        )
                    }
                }
            }
        }

    LaunchedEffect(uiState.operationError) {
        val error = uiState.operationError ?: return@LaunchedEffect
        if (gitFeature.shouldShowGitConflictDialog(error)) {
            dialogState.gitConflictMessage = error
            dialogState.showGitConflictResolutionDialog = true
        } else {
            snackbarHostState.showSnackbar(
                gitFeature.presentGitSyncErrorMessage(
                    message = error,
                    conflictSummary = gitConflictSummary,
                    directPathRequired = gitDirectPathRequired,
                    unknownError = unknownErrorMessage,
                ),
            )
        }
        viewModel.clearOperationError()
    }

    LaunchedEffect(uiState.git.syncState) {
        val errorState = uiState.git.syncState as? SyncEngineState.Error ?: return@LaunchedEffect
        if (gitFeature.shouldShowGitConflictDialog(errorState.message) && !dialogState.showGitConflictResolutionDialog) {
            dialogState.gitConflictMessage = errorState.message
            dialogState.showGitConflictResolutionDialog = true
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    AnimatedContent(
        targetState = currentLanguageTag,
        transitionSpec = {
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
        },
        label = "SettingsLanguageTransition",
    ) { languageTag ->
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                haptic.medium()
                                onBackClick()
                            },
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
            },
        ) { padding ->
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
                StorageSettingsSection(
                    state = uiState.storage,
                    onSelectRoot = { rootLauncher.launch(null) },
                    onSelectImageRoot = { imageLauncher.launch(null) },
                    onSelectVoiceRoot = { voiceLauncher.launch(null) },
                    onOpenFilenameFormatDialog = { dialogState.showFilenameDialog = true },
                    onOpenTimestampFormatDialog = { dialogState.showTimestampDialog = true },
                )

                DisplaySettingsSection(
                    state = uiState.display,
                    languageLabel = languageLabels[languageTag] ?: languageTag,
                    themeLabel = themeModeLabels[uiState.display.themeMode] ?: uiState.display.themeMode.value,
                    onOpenLanguageDialog = { dialogState.showLanguageDialog = true },
                    onOpenThemeDialog = { dialogState.showThemeDialog = true },
                    onOpenDateFormatDialog = { dialogState.showDateDialog = true },
                    onOpenTimeFormatDialog = { dialogState.showTimeDialog = true },
                )

                LanShareSettingsSection(
                    state = uiState.lanShare,
                    onToggleE2e = { enabled ->
                        lanShareFeature.updateLanShareE2eEnabled(enabled)
                        if (
                            LanSharePairingDialogTriggerPolicy.shouldShowOnE2eEnabled(
                                enabled = enabled,
                                pairingConfigured = uiState.lanShare.pairingConfigured,
                            )
                        ) {
                            dialogState.lanPairingCodeInput = ""
                            dialogState.lanPairingCodeVisible = false
                            lanShareFeature.clearPairingCodeError()
                            dialogState.showLanPairingDialog = true
                        }
                    },
                    onOpenPairingDialog = {
                        dialogState.lanPairingCodeVisible = false
                        lanShareFeature.clearPairingCodeError()
                        dialogState.showLanPairingDialog = true
                    },
                    onOpenDeviceNameDialog = {
                        dialogState.deviceNameInput = uiState.lanShare.deviceName
                        dialogState.showDeviceNameDialog = true
                    },
                )

                ShareCardSettingsSection(
                    state = uiState.shareCard,
                    styleLabel = shareCardStyleLabels[uiState.shareCard.style] ?: uiState.shareCard.style.value,
                    onOpenStyleDialog = { dialogState.showShareCardStyleDialog = true },
                    onToggleShowTime = shareCardFeature::updateShareCardShowTime,
                    onToggleShowBrand = shareCardFeature::updateShareCardShowBrand,
                )

                GitSyncSettingsSection(
                    state = uiState.git,
                    syncIntervalLabel = gitSyncIntervalLabels[uiState.git.autoSyncInterval] ?: uiState.git.autoSyncInterval,
                    syncNowSubtitle =
                        SettingsErrorPresenter.gitSyncNowSubtitle(
                            state = uiState.git.syncState,
                            lastSyncTime = uiState.git.lastSyncTime,
                            localizeError = { message ->
                                gitFeature.presentGitSyncErrorMessage(
                                    message = message,
                                    conflictSummary = gitConflictSummary,
                                    directPathRequired = gitDirectPathRequired,
                                    unknownError = unknownErrorMessage,
                                )
                            },
                        ),
                    connectionSubtitle = connectionTestSubtitle(uiState.git.connectionTestState),
                    onToggleEnabled = gitFeature::updateGitSyncEnabled,
                    onOpenRemoteUrlDialog = {
                        dialogState.gitRemoteUrlInput = uiState.git.remoteUrl
                        dialogState.showGitRemoteUrlDialog = true
                    },
                    onOpenPatDialog = {
                        dialogState.gitPatInput = ""
                        dialogState.gitPatVisible = false
                        dialogState.showGitPatDialog = true
                    },
                    onOpenAuthorNameDialog = {
                        dialogState.gitAuthorNameInput = uiState.git.authorName
                        dialogState.showGitAuthorNameDialog = true
                    },
                    onOpenAuthorEmailDialog = {
                        dialogState.gitAuthorEmailInput = uiState.git.authorEmail
                        dialogState.showGitAuthorEmailDialog = true
                    },
                    onToggleAutoSync = gitFeature::updateGitAutoSyncEnabled,
                    onOpenSyncIntervalDialog = { dialogState.showGitSyncIntervalDialog = true },
                    onToggleSyncOnRefresh = gitFeature::updateGitSyncOnRefresh,
                    onSyncNow = {
                        if (uiState.git.syncState !is SyncEngineState.Syncing &&
                            uiState.git.syncState !is SyncEngineState.Initializing
                        ) {
                            gitFeature.triggerGitSyncNow()
                        }
                    },
                    onTestConnection = {
                        gitFeature.resetConnectionTestState()
                        gitFeature.testGitConnection()
                    },
                    onOpenResetDialog = { dialogState.showGitResetConfirmDialog = true },
                )

                InteractionSettingsSection(
                    state = uiState.interaction,
                    onToggleHaptic = interactionFeature::updateHapticFeedback,
                    onToggleInputHints = interactionFeature::updateShowInputHints,
                    onToggleDoubleTapEdit = interactionFeature::updateDoubleTapEditEnabled,
                    onToggleAppLock = interactionFeature::updateAppLockEnabled,
                )

                SystemSettingsSection(
                    state = uiState.system,
                    onToggleCheckUpdates = systemFeature::updateCheckUpdatesOnStartup,
                )

                AboutSettingsSection(
                    onOpenGithub = { uriHandler.openUri("https://github.com/unsigned57/lomo") },
                )
            }
        }
    }

    SettingsDialogHost(
        uiState = uiState,
        storageFeature = storageFeature,
        displayFeature = displayFeature,
        shareCardFeature = shareCardFeature,
        lanShareFeature = lanShareFeature,
        gitFeature = gitFeature,
        dialogState = dialogState,
        options =
            SettingsDialogOptions(
                dateFormats = dateFormats,
                timeFormats = timeFormats,
                themeModes = themeModes,
                shareCardStyles = shareCardStyles,
                filenameFormats = filenameFormats,
                timestampFormats = timestampFormats,
                gitSyncIntervals = gitSyncIntervals,
                languageTag = currentLanguageTag,
                languageLabels = languageLabels,
                themeModeLabels = themeModeLabels,
                shareCardStyleLabels = shareCardStyleLabels,
                gitSyncIntervalLabels = gitSyncIntervalLabels,
            ),
        onApplyLanguageTag = { tag ->
            val locales =
                if (tag == "system") {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tag)
                }
            AppCompatDelegate.setApplicationLocales(locales)
        },
        gitConflictSummary = gitConflictSummary,
        gitDirectPathRequired = gitDirectPathRequired,
        unknownErrorMessage = unknownErrorMessage,
    )
}
