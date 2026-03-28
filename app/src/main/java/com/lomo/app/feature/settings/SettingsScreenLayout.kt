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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.util.LocalAppHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenScaffold(
    uiState: SettingsScreenUiState,
    onBackClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    dialogState: SettingsDialogState,
    features: SettingsFeatures,
    resources: SettingsResources,
    storagePickers: StoragePickerActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    AnimatedContent(
        targetState = resources.currentLanguageTag,
        transitionSpec = settingsLanguageTransitionSpec(),
        label = "SettingsLanguageTransition",
    ) { languageTag ->
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
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
                languageTag = languageTag,
                dialogState = dialogState,
                features = features,
                dialogOptions = resources.dialogOptions,
                storagePickers = storagePickers,
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
    languageTag: String,
    dialogState: SettingsDialogState,
    features: SettingsFeatures,
    dialogOptions: SettingsDialogOptions,
    storagePickers: StoragePickerActions,
) {
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
            gitSyncIntervalLabels = dialogOptions.gitSyncIntervalLabels,
        )
        WebDavSyncSettingsSectionContainer(
            state = uiState.webDav,
            dialogState = dialogState,
            webDavFeature = features.webDav,
            gitSyncIntervalLabels = dialogOptions.gitSyncIntervalLabels,
            webDavProviderLabels = dialogOptions.webDavProviderLabels,
        )
        FooterSettingsSections(
            uiState = uiState,
            interactionFeature = features.interaction,
            systemFeature = features.system,
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
}

@Composable
private fun FooterSettingsSections(
    uiState: SettingsScreenUiState,
    interactionFeature: SettingsInteractionFeatureViewModel,
    systemFeature: SettingsSystemFeatureViewModel,
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
    AboutSettingsSection(onOpenGithub = { uriHandler.openUri(GITHUB_URL) })
}
