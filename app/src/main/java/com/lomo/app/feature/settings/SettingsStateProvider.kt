package com.lomo.app.feature.settings

import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.CustomFontInfo
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.WebDavProvider
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SettingsStateProvider(
    appConfigCoordinator: SettingsAppConfigCoordinator,
    lanShareCoordinator: SettingsLanShareCoordinator,
    gitCoordinator: SettingsGitCoordinator,
    webDavCoordinator: SettingsWebDavCoordinator,
    s3Coordinator: SettingsS3Coordinator,
    val operationError: StateFlow<SettingsOperationError?>,
    scope: CoroutineScope,
) {
    private data class GitExtensionState(
        val remoteUrl: String,
        val authorName: String,
        val authorEmail: String,
    )

    private data class WebDavExtensionState(
        val provider: WebDavProvider,
        val baseUrl: String,
        val endpointUrl: String,
        val username: String,
    )

    private data class InteractionPrimaryState(
        val hapticEnabled: Boolean,
        val showInputHints: Boolean,
        val doubleTapEditEnabled: Boolean,
    )

    private data class InteractionSecondaryState(
        val quickSaveOnBackEnabled: Boolean,
        val scrollbarEnabled: Boolean,
    )

    private data class PrimaryUiSections(
        val storage: StorageSectionState,
        val display: DisplaySectionState,
        val lanShare: LanShareSectionState,
        val shareCard: ShareCardSectionState,
        val snapshot: SnapshotSectionState,
    )

    private data class CoreUiSections(
        val storage: StorageSectionState,
        val display: DisplaySectionState,
        val lanShare: LanShareSectionState,
        val shareCard: ShareCardSectionState,
        val snapshot: SnapshotSectionState,
        val git: GitSectionState,
        val webDav: WebDavSectionState,
        val s3: S3SectionState,
    )

    private data class StorageDirectoryInputs(
        val rootDirectory: DirectoryDisplayState,
        val imageDirectory: DirectoryDisplayState,
        val voiceDirectory: DirectoryDisplayState,
        val syncInboxEnabled: Boolean,
    )

    val pairingCodeError: StateFlow<String?> = lanShareCoordinator.pairingCodeError
    val connectionTestState: StateFlow<RemoteProviderConnectionTestState> = gitCoordinator.connectionTestState
    val webDavConnectionTestState: StateFlow<RemoteProviderConnectionTestState> = webDavCoordinator.connectionTestState
    private val s3StateProvider = SettingsS3StateProvider(s3Coordinator = s3Coordinator, scope = scope)
    val s3ConnectionTestState: StateFlow<RemoteProviderConnectionTestState> = s3StateProvider.connectionTestState

    private val storageState: StateFlow<StorageSectionState> =
        combine(
            combine(
                appConfigCoordinator.rootDirectory,
                appConfigCoordinator.imageDirectory,
                appConfigCoordinator.voiceDirectory,
                appConfigCoordinator.syncInboxEnabled,
            ) { rootDirectory, imageDirectory, voiceDirectory, syncInboxEnabled ->
                StorageDirectoryInputs(
                    rootDirectory = rootDirectory,
                    imageDirectory = imageDirectory,
                    voiceDirectory = voiceDirectory,
                    syncInboxEnabled = syncInboxEnabled,
                )
            },
            appConfigCoordinator.syncInboxDirectory,
            appConfigCoordinator.storageFilenameFormat,
            appConfigCoordinator.storageTimestampFormat,
        ) { directories, syncInboxDirectory, filenameFormat, timestampFormat ->
            StorageSectionState(
                rootDirectory = directories.rootDirectory,
                imageDirectory = directories.imageDirectory,
                voiceDirectory = directories.voiceDirectory,
                syncInboxEnabled = directories.syncInboxEnabled,
                syncInboxDirectory = syncInboxDirectory,
                filenameFormat = filenameFormat,
                timestampFormat = timestampFormat,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                StorageSectionState(
                    rootDirectory = appConfigCoordinator.rootDirectory.value,
                    imageDirectory = appConfigCoordinator.imageDirectory.value,
                    voiceDirectory = appConfigCoordinator.voiceDirectory.value,
                    syncInboxEnabled = appConfigCoordinator.syncInboxEnabled.value,
                    syncInboxDirectory = appConfigCoordinator.syncInboxDirectory.value,
                    filenameFormat = appConfigCoordinator.storageFilenameFormat.value,
                    timestampFormat = appConfigCoordinator.storageTimestampFormat.value,
                ),
        )

    private val displayState: StateFlow<DisplaySectionState> =
        combine(
            appConfigCoordinator.dateFormat,
            appConfigCoordinator.timeFormat,
            appConfigCoordinator.themeMode,
            combine(
                appConfigCoordinator.colorSource,
                appConfigCoordinator.fontPreference,
                appConfigCoordinator.availableCustomFonts,
                appConfigCoordinator.colorHistory,
            ) { colorSource, fontPreference, customFonts, colorHistory ->
                DisplaySources(
                    colorSource = colorSource,
                    fontPreference = fontPreference,
                    customFonts = customFonts,
                    colorHistory = colorHistory
                )
            },
            combine(
                appConfigCoordinator.typographyFontSizeScale,
                appConfigCoordinator.typographyLineHeightScale,
                appConfigCoordinator.typographyLetterSpacingScale,
                appConfigCoordinator.typographyParagraphSpacingScale,
            ) { fontSize, lineHeight, letterSpacing, paragraphSpacing ->
                floatArrayOf(fontSize, lineHeight, letterSpacing, paragraphSpacing)
            }
        ) { dateFormat, timeFormat, themeMode, sources, typography ->
            DisplaySectionState(
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                themeMode = themeMode,
                colorSource = sources.colorSource,
                fontPreference = sources.fontPreference,
                availableCustomFonts = sources.customFonts.toImmutableList(),
                colorHistory = sources.colorHistory.toImmutableList(),
                typographyFontSizeScale = typography[0],
                typographyLineHeightScale = typography[1],
                typographyLetterSpacingScale = typography[2],
                typographyParagraphSpacingScale = typography[3],
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                DisplaySectionState(
                    dateFormat = appConfigCoordinator.dateFormat.value,
                    timeFormat = appConfigCoordinator.timeFormat.value,
                    themeMode = appConfigCoordinator.themeMode.value,
                    colorSource = appConfigCoordinator.colorSource.value,
                    fontPreference = appConfigCoordinator.fontPreference.value,
                    availableCustomFonts = appConfigCoordinator.availableCustomFonts.value.toImmutableList(),
                    colorHistory = appConfigCoordinator.colorHistory.value.toImmutableList(),
                    typographyFontSizeScale = appConfigCoordinator.typographyFontSizeScale.value,
                    typographyLineHeightScale = appConfigCoordinator.typographyLineHeightScale.value,
                    typographyLetterSpacingScale = appConfigCoordinator.typographyLetterSpacingScale.value,
                    typographyParagraphSpacingScale = appConfigCoordinator.typographyParagraphSpacingScale.value,
                ),
        )

    private data class DisplaySources(
        val colorSource: ColorSource,
        val fontPreference: FontPreference,
        val customFonts: List<CustomFontInfo>,
        val colorHistory: List<Int>,
    )

    private val lanShareState: StateFlow<LanShareSectionState> =
        combine(
            lanShareCoordinator.lanShareEnabled,
            lanShareCoordinator.lanShareE2eEnabled,
            lanShareCoordinator.lanSharePairingConfigured,
            lanShareCoordinator.lanShareDeviceName,
            lanShareCoordinator.pairingCodeError,
        ) { enabled, e2eEnabled, pairingConfigured, deviceName, pairingCodeError ->
            LanShareSectionState(enabled, e2eEnabled, pairingConfigured, deviceName, pairingCodeError)
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                LanShareSectionState(
                    enabled = lanShareCoordinator.lanShareEnabled.value,
                    e2eEnabled = lanShareCoordinator.lanShareE2eEnabled.value,
                    pairingConfigured = lanShareCoordinator.lanSharePairingConfigured.value,
                    deviceName = lanShareCoordinator.lanShareDeviceName.value,
                    pairingCodeError = lanShareCoordinator.pairingCodeError.value,
                ),
        )

    private val shareCardState: StateFlow<ShareCardSectionState> =
        combine(
            appConfigCoordinator.shareCardShowTime,
            appConfigCoordinator.shareCardShowBrand,
            appConfigCoordinator.shareCardSignatureText,
        ) { showTime, showBrand, signatureText ->
            ShareCardSectionState(showTime, showBrand, signatureText)
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                ShareCardSectionState(
                    appConfigCoordinator.shareCardShowTime.value,
                    appConfigCoordinator.shareCardShowBrand.value,
                    appConfigCoordinator.shareCardSignatureText.value,
                ),
        )

    private val memoSnapshotInputs: StateFlow<Triple<Boolean, Int, Int>> =
        combine(
            appConfigCoordinator.memoSnapshotsEnabled,
            appConfigCoordinator.memoSnapshotMaxCount,
            appConfigCoordinator.memoSnapshotMaxAgeDays,
        ) { enabled, count, days ->
            Triple(enabled, count, days)
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                Triple(
                    appConfigCoordinator.memoSnapshotsEnabled.value,
                    appConfigCoordinator.memoSnapshotMaxCount.value,
                    appConfigCoordinator.memoSnapshotMaxAgeDays.value,
                ),
        )

    private val snapshotState: StateFlow<SnapshotSectionState> =
        memoSnapshotInputs.map { memoInputs ->
            SnapshotSectionState(
                memoSnapshotsEnabled = memoInputs.first,
                memoSnapshotMaxCount = memoInputs.second,
                memoSnapshotMaxAgeDays = memoInputs.third,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                SnapshotSectionState(
                    memoSnapshotsEnabled = appConfigCoordinator.memoSnapshotsEnabled.value,
                    memoSnapshotMaxCount = appConfigCoordinator.memoSnapshotMaxCount.value,
                    memoSnapshotMaxAgeDays = appConfigCoordinator.memoSnapshotMaxAgeDays.value,
                ),
        )

    private val gitExtensionState: StateFlow<GitExtensionState> =
        combine(
            gitCoordinator.gitRemoteUrl,
            gitCoordinator.gitAuthorName,
            gitCoordinator.gitAuthorEmail,
        ) { remoteUrl, authorName, authorEmail ->
            GitExtensionState(remoteUrl, authorName, authorEmail)
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                GitExtensionState(
                    remoteUrl = gitCoordinator.gitRemoteUrl.value,
                    authorName = gitCoordinator.gitAuthorName.value,
                    authorEmail = gitCoordinator.gitAuthorEmail.value,
                ),
        )

    private val gitState: StateFlow<GitSectionState> =
        combine(
            gitCoordinator.providerSettingsModel,
            gitExtensionState,
            gitCoordinator.resetInProgress,
        ) { providerSettings, extension, resetInProgress ->
            GitSectionState(
                providerSettings = providerSettings,
                remoteUrl = extension.remoteUrl,
                authorName = extension.authorName,
                authorEmail = extension.authorEmail,
                resetInProgress = resetInProgress,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                GitSectionState(
                    providerSettings = gitCoordinator.providerSettingsModel.value,
                    remoteUrl = gitExtensionState.value.remoteUrl,
                    authorName = gitExtensionState.value.authorName,
                    authorEmail = gitExtensionState.value.authorEmail,
                    resetInProgress = gitCoordinator.resetInProgress.value,
                ),
        )

    private val webDavBaseExtensionState: StateFlow<Triple<WebDavProvider, String, String>> =
        combine(
            webDavCoordinator.webDavProvider,
            webDavCoordinator.webDavBaseUrl,
            webDavCoordinator.webDavEndpointUrl,
        ) { provider, baseUrl, endpointUrl ->
            Triple(provider, baseUrl, endpointUrl)
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                Triple(
                    webDavCoordinator.webDavProvider.value,
                    webDavCoordinator.webDavBaseUrl.value,
                    webDavCoordinator.webDavEndpointUrl.value,
                ),
        )

    private val webDavExtensionState: StateFlow<WebDavExtensionState> =
        combine(
            webDavBaseExtensionState,
            webDavCoordinator.webDavUsername,
        ) { baseInputs, username ->
            WebDavExtensionState(
                provider = baseInputs.first,
                baseUrl = baseInputs.second,
                endpointUrl = baseInputs.third,
                username = username,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                WebDavExtensionState(
                    provider = webDavCoordinator.webDavProvider.value,
                    baseUrl = webDavCoordinator.webDavBaseUrl.value,
                    endpointUrl = webDavCoordinator.webDavEndpointUrl.value,
                    username = webDavCoordinator.webDavUsername.value,
                ),
        )

    private val webDavState: StateFlow<WebDavSectionState> =
        combine(
            webDavCoordinator.providerSettingsModel,
            webDavExtensionState,
        ) { providerSettings, extension ->
            WebDavSectionState(
                providerSettings = providerSettings,
                provider = extension.provider,
                baseUrl = extension.baseUrl,
                endpointUrl = extension.endpointUrl,
                username = extension.username,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                WebDavSectionState(
                    providerSettings = webDavCoordinator.providerSettingsModel.value,
                    provider = webDavExtensionState.value.provider,
                    baseUrl = webDavExtensionState.value.baseUrl,
                    endpointUrl = webDavExtensionState.value.endpointUrl,
                    username = webDavExtensionState.value.username,
                ),
        )

    private val s3State: StateFlow<S3SectionState> = s3StateProvider.sectionState

    private val interactionPrimaryState: StateFlow<InteractionPrimaryState> =
        combine(
            appConfigCoordinator.hapticFeedbackEnabled,
            appConfigCoordinator.showInputHints,
            appConfigCoordinator.doubleTapEditEnabled,
        ) { hapticEnabled, showInputHints, doubleTapEditEnabled ->
            InteractionPrimaryState(
                hapticEnabled = hapticEnabled,
                showInputHints = showInputHints,
                doubleTapEditEnabled = doubleTapEditEnabled,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                InteractionPrimaryState(
                    hapticEnabled = appConfigCoordinator.hapticFeedbackEnabled.value,
                    showInputHints = appConfigCoordinator.showInputHints.value,
                    doubleTapEditEnabled = appConfigCoordinator.doubleTapEditEnabled.value,
                ),
        )

    private val interactionState: StateFlow<InteractionSectionState> =
        combine(
            interactionPrimaryState,
            appConfigCoordinator.freeTextCopyEnabled,
            appConfigCoordinator.memoActionAutoReorderEnabled,
            appConfigCoordinator.appLockEnabled,
            combine(
                appConfigCoordinator.quickSaveOnBackEnabled,
                appConfigCoordinator.scrollbarEnabled,
            ) { quickSave, scrollbar ->
                InteractionSecondaryState(quickSave, scrollbar)
            },
        ) {
                primary,
                freeTextCopyEnabled,
                memoActionAutoReorderEnabled,
                appLockEnabled,
                secondary,
            ->
            InteractionSectionState(
                hapticEnabled = primary.hapticEnabled,
                showInputHints = primary.showInputHints,
                doubleTapEditEnabled = primary.doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
                appLockEnabled = appLockEnabled,
                quickSaveOnBackEnabled = secondary.quickSaveOnBackEnabled,
                scrollbarEnabled = secondary.scrollbarEnabled,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                InteractionSectionState(
                    hapticEnabled = appConfigCoordinator.hapticFeedbackEnabled.value,
                    showInputHints = appConfigCoordinator.showInputHints.value,
                    doubleTapEditEnabled = appConfigCoordinator.doubleTapEditEnabled.value,
                    freeTextCopyEnabled = appConfigCoordinator.freeTextCopyEnabled.value,
                    memoActionAutoReorderEnabled = appConfigCoordinator.memoActionAutoReorderEnabled.value,
                    appLockEnabled = appConfigCoordinator.appLockEnabled.value,
                    quickSaveOnBackEnabled = appConfigCoordinator.quickSaveOnBackEnabled.value,
                    scrollbarEnabled = appConfigCoordinator.scrollbarEnabled.value,
                ),
        )

    private val systemState: StateFlow<SystemSectionState> =
        appConfigCoordinator.checkUpdatesOnStartup
            .map(::SystemSectionState)
            .stateIn(
                scope = scope,
                started = settingsWhileSubscribed(),
                initialValue = SystemSectionState(appConfigCoordinator.checkUpdatesOnStartup.value),
            )

    private val primaryUiSections: StateFlow<PrimaryUiSections> =
        combine(
            storageState,
            displayState,
            lanShareState,
            shareCardState,
            snapshotState,
        ) { storage, display, lanShare, shareCard, snapshot ->
            PrimaryUiSections(
                storage = storage,
                display = display,
                lanShare = lanShare,
                shareCard = shareCard,
                snapshot = snapshot,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                PrimaryUiSections(
                    storage = storageState.value,
                    display = displayState.value,
                    lanShare = lanShareState.value,
                    shareCard = shareCardState.value,
                    snapshot = snapshotState.value,
                ),
        )

    private val coreUiSections: StateFlow<CoreUiSections> =
        combine(primaryUiSections, gitState, webDavState, s3State) { primary, git, webDav, s3 ->
            CoreUiSections(
                storage = primary.storage,
                display = primary.display,
                lanShare = primary.lanShare,
                shareCard = primary.shareCard,
                snapshot = primary.snapshot,
                git = git,
                webDav = webDav,
                s3 = s3,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                CoreUiSections(
                    storage = storageState.value,
                    display = displayState.value,
                    lanShare = lanShareState.value,
                    shareCard = shareCardState.value,
                    snapshot = snapshotState.value,
                    git = gitState.value,
                    webDav = webDavState.value,
                    s3 = s3State.value,
                ),
        )

    val uiState: StateFlow<SettingsScreenUiState> =
        combine(
            coreUiSections,
            interactionState,
            systemState,
            operationError,
        ) { core, interaction, system, operationError ->
            SettingsScreenUiState(
                storage = core.storage,
                display = core.display,
                lanShare = core.lanShare,
                shareCard = core.shareCard,
                snapshot = core.snapshot,
                git = core.git,
                webDav = core.webDav,
                s3 = core.s3,
                interaction = interaction,
                system = system,
                operationError = operationError,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                SettingsScreenUiState(
                    storage = coreUiSections.value.storage,
                    display = coreUiSections.value.display,
                    lanShare = coreUiSections.value.lanShare,
                    shareCard = coreUiSections.value.shareCard,
                    snapshot = coreUiSections.value.snapshot,
                    git = coreUiSections.value.git,
                    webDav = coreUiSections.value.webDav,
                    s3 = coreUiSections.value.s3,
                    interaction = interactionState.value,
                    system = systemState.value,
                    operationError = operationError.value,
                ),
        )
}
