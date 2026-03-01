package com.lomo.app.feature.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SettingsStateProvider(
    appConfigCoordinator: SettingsAppConfigCoordinator,
    lanShareCoordinator: SettingsLanShareCoordinator,
    gitCoordinator: SettingsGitCoordinator,
    val operationError: StateFlow<String?>,
    scope: CoroutineScope,
) {
    private data class GitIdentityState(
        val enabled: Boolean,
        val remoteUrl: String,
        val patConfigured: Boolean,
        val authorName: String,
        val authorEmail: String,
    )

    private data class GitSyncSettingsState(
        val autoSyncEnabled: Boolean,
        val autoSyncInterval: String,
        val syncOnRefreshEnabled: Boolean,
        val lastSyncTime: Long,
        val syncState: com.lomo.domain.model.SyncEngineState,
    )

    private data class CoreUiSections(
        val storage: StorageSectionState,
        val display: DisplaySectionState,
        val lanShare: LanShareSectionState,
        val shareCard: ShareCardSectionState,
        val git: GitSectionState,
    )

    val pairingCodeError: StateFlow<String?> = lanShareCoordinator.pairingCodeError
    val connectionTestState: StateFlow<SettingsGitConnectionTestState> = gitCoordinator.connectionTestState

    private val storageState: StateFlow<StorageSectionState> =
        combine(
            appConfigCoordinator.rootDirectory,
            appConfigCoordinator.imageDirectory,
            appConfigCoordinator.voiceDirectory,
            appConfigCoordinator.storageFilenameFormat,
            appConfigCoordinator.storageTimestampFormat,
        ) { rootDirectory, imageDirectory, voiceDirectory, filenameFormat, timestampFormat ->
            StorageSectionState(
                rootDirectory = rootDirectory,
                imageDirectory = imageDirectory,
                voiceDirectory = voiceDirectory,
                filenameFormat = filenameFormat,
                timestampFormat = timestampFormat,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                StorageSectionState(
                    rootDirectory = appConfigCoordinator.rootDirectory.value,
                    imageDirectory = appConfigCoordinator.imageDirectory.value,
                    voiceDirectory = appConfigCoordinator.voiceDirectory.value,
                    filenameFormat = appConfigCoordinator.storageFilenameFormat.value,
                    timestampFormat = appConfigCoordinator.storageTimestampFormat.value,
                ),
        )

    private val displayState: StateFlow<DisplaySectionState> =
        combine(
            appConfigCoordinator.dateFormat,
            appConfigCoordinator.timeFormat,
            appConfigCoordinator.themeMode,
        ) { dateFormat, timeFormat, themeMode ->
            DisplaySectionState(
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                themeMode = themeMode,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                DisplaySectionState(
                    dateFormat = appConfigCoordinator.dateFormat.value,
                    timeFormat = appConfigCoordinator.timeFormat.value,
                    themeMode = appConfigCoordinator.themeMode.value,
                ),
        )

    private val lanShareState: StateFlow<LanShareSectionState> =
        combine(
            lanShareCoordinator.lanShareE2eEnabled,
            lanShareCoordinator.lanSharePairingConfigured,
            lanShareCoordinator.lanShareDeviceName,
            lanShareCoordinator.pairingCodeError,
        ) { e2eEnabled, pairingConfigured, deviceName, pairingCodeError ->
            LanShareSectionState(
                e2eEnabled = e2eEnabled,
                pairingConfigured = pairingConfigured,
                deviceName = deviceName,
                pairingCodeError = pairingCodeError,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                LanShareSectionState(
                    e2eEnabled = lanShareCoordinator.lanShareE2eEnabled.value,
                    pairingConfigured = lanShareCoordinator.lanSharePairingConfigured.value,
                    deviceName = lanShareCoordinator.lanShareDeviceName.value,
                    pairingCodeError = lanShareCoordinator.pairingCodeError.value,
                ),
        )

    private val shareCardState: StateFlow<ShareCardSectionState> =
        combine(
            appConfigCoordinator.shareCardStyle,
            appConfigCoordinator.shareCardShowTime,
            appConfigCoordinator.shareCardShowBrand,
        ) { style, showTime, showBrand ->
            ShareCardSectionState(
                style = style,
                showTime = showTime,
                showBrand = showBrand,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                ShareCardSectionState(
                    style = appConfigCoordinator.shareCardStyle.value,
                    showTime = appConfigCoordinator.shareCardShowTime.value,
                    showBrand = appConfigCoordinator.shareCardShowBrand.value,
                ),
        )

    private val gitIdentityState: StateFlow<GitIdentityState> =
        combine(
            gitCoordinator.gitSyncEnabled,
            gitCoordinator.gitRemoteUrl,
            gitCoordinator.gitPatConfigured,
            gitCoordinator.gitAuthorName,
            gitCoordinator.gitAuthorEmail,
        ) { enabled, remoteUrl, patConfigured, authorName, authorEmail ->
            GitIdentityState(
                enabled = enabled,
                remoteUrl = remoteUrl,
                patConfigured = patConfigured,
                authorName = authorName,
                authorEmail = authorEmail,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                GitIdentityState(
                    enabled = gitCoordinator.gitSyncEnabled.value,
                    remoteUrl = gitCoordinator.gitRemoteUrl.value,
                    patConfigured = gitCoordinator.gitPatConfigured.value,
                    authorName = gitCoordinator.gitAuthorName.value,
                    authorEmail = gitCoordinator.gitAuthorEmail.value,
                ),
        )

    private val gitSyncSettingsState: StateFlow<GitSyncSettingsState> =
        combine(
            gitCoordinator.gitAutoSyncEnabled,
            gitCoordinator.gitAutoSyncInterval,
            gitCoordinator.gitSyncOnRefreshEnabled,
            gitCoordinator.gitLastSyncTime,
            gitCoordinator.gitSyncState,
        ) { autoSyncEnabled, autoSyncInterval, syncOnRefreshEnabled, lastSyncTime, syncState ->
            GitSyncSettingsState(
                autoSyncEnabled = autoSyncEnabled,
                autoSyncInterval = autoSyncInterval,
                syncOnRefreshEnabled = syncOnRefreshEnabled,
                lastSyncTime = lastSyncTime,
                syncState = syncState,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                GitSyncSettingsState(
                    autoSyncEnabled = gitCoordinator.gitAutoSyncEnabled.value,
                    autoSyncInterval = gitCoordinator.gitAutoSyncInterval.value,
                    syncOnRefreshEnabled = gitCoordinator.gitSyncOnRefreshEnabled.value,
                    lastSyncTime = gitCoordinator.gitLastSyncTime.value,
                    syncState = gitCoordinator.gitSyncState.value,
                ),
        )

    private val gitState: StateFlow<GitSectionState> =
        combine(
            gitIdentityState,
            gitSyncSettingsState,
            gitCoordinator.connectionTestState,
            gitCoordinator.resetInProgress,
        ) { identity, syncSettings, connectionTestState, resetInProgress ->
            GitSectionState(
                enabled = identity.enabled,
                remoteUrl = identity.remoteUrl,
                patConfigured = identity.patConfigured,
                authorName = identity.authorName,
                authorEmail = identity.authorEmail,
                autoSyncEnabled = syncSettings.autoSyncEnabled,
                autoSyncInterval = syncSettings.autoSyncInterval,
                syncOnRefreshEnabled = syncSettings.syncOnRefreshEnabled,
                lastSyncTime = syncSettings.lastSyncTime,
                syncState = syncSettings.syncState,
                connectionTestState = connectionTestState,
                resetInProgress = resetInProgress,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                GitSectionState(
                    enabled = gitIdentityState.value.enabled,
                    remoteUrl = gitIdentityState.value.remoteUrl,
                    patConfigured = gitIdentityState.value.patConfigured,
                    authorName = gitIdentityState.value.authorName,
                    authorEmail = gitIdentityState.value.authorEmail,
                    autoSyncEnabled = gitSyncSettingsState.value.autoSyncEnabled,
                    autoSyncInterval = gitSyncSettingsState.value.autoSyncInterval,
                    syncOnRefreshEnabled = gitSyncSettingsState.value.syncOnRefreshEnabled,
                    lastSyncTime = gitSyncSettingsState.value.lastSyncTime,
                    syncState = gitSyncSettingsState.value.syncState,
                    connectionTestState = gitCoordinator.connectionTestState.value,
                    resetInProgress = gitCoordinator.resetInProgress.value,
                ),
        )

    private val interactionState: StateFlow<InteractionSectionState> =
        combine(
            appConfigCoordinator.hapticFeedbackEnabled,
            appConfigCoordinator.showInputHints,
            appConfigCoordinator.doubleTapEditEnabled,
        ) { hapticEnabled, showInputHints, doubleTapEditEnabled ->
            InteractionSectionState(
                hapticEnabled = hapticEnabled,
                showInputHints = showInputHints,
                doubleTapEditEnabled = doubleTapEditEnabled,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                InteractionSectionState(
                    hapticEnabled = appConfigCoordinator.hapticFeedbackEnabled.value,
                    showInputHints = appConfigCoordinator.showInputHints.value,
                    doubleTapEditEnabled = appConfigCoordinator.doubleTapEditEnabled.value,
                ),
        )

    private val systemState: StateFlow<SystemSectionState> =
        appConfigCoordinator.checkUpdatesOnStartup
            .map { checkUpdatesOnStartup ->
                SystemSectionState(checkUpdatesOnStartup = checkUpdatesOnStartup)
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = SystemSectionState(checkUpdatesOnStartup = appConfigCoordinator.checkUpdatesOnStartup.value),
            )

    private val coreUiSections: StateFlow<CoreUiSections> =
        combine(
            storageState,
            displayState,
            lanShareState,
            shareCardState,
            gitState,
        ) { storage, display, lanShare, shareCard, git ->
            CoreUiSections(
                storage = storage,
                display = display,
                lanShare = lanShare,
                shareCard = shareCard,
                git = git,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                CoreUiSections(
                    storage = storageState.value,
                    display = displayState.value,
                    lanShare = lanShareState.value,
                    shareCard = shareCardState.value,
                    git = gitState.value,
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
                git = core.git,
                interaction = interaction,
                system = system,
                operationError = operationError,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                SettingsScreenUiState(
                    storage = coreUiSections.value.storage,
                    display = coreUiSections.value.display,
                    lanShare = coreUiSections.value.lanShare,
                    shareCard = coreUiSections.value.shareCard,
                    git = coreUiSections.value.git,
                    interaction = interactionState.value,
                    system = systemState.value,
                    operationError = operationError.value,
                ),
        )
}
