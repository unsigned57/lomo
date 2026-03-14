package com.lomo.app.feature.settings

import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncState
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
    webDavCoordinator: SettingsWebDavCoordinator,
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

    private data class WebDavIdentityState(
        val enabled: Boolean,
        val provider: WebDavProvider,
        val baseUrl: String,
        val endpointUrl: String,
        val username: String,
        val passwordConfigured: Boolean,
    )

    private data class WebDavSyncSettingsState(
        val autoSyncEnabled: Boolean,
        val autoSyncInterval: String,
        val syncOnRefreshEnabled: Boolean,
        val lastSyncTime: Long,
        val syncState: WebDavSyncState,
    )

    private data class CoreUiSections(
        val storage: StorageSectionState,
        val display: DisplaySectionState,
        val lanShare: LanShareSectionState,
        val shareCard: ShareCardSectionState,
        val git: GitSectionState,
        val webDav: WebDavSectionState,
    )

    val pairingCodeError: StateFlow<String?> = lanShareCoordinator.pairingCodeError
    val connectionTestState: StateFlow<SettingsGitConnectionTestState> = gitCoordinator.connectionTestState
    val webDavConnectionTestState: StateFlow<SettingsWebDavConnectionTestState> = webDavCoordinator.connectionTestState

    private val storageState: StateFlow<StorageSectionState> =
        combine(
            appConfigCoordinator.rootDirectory,
            appConfigCoordinator.imageDirectory,
            appConfigCoordinator.voiceDirectory,
            appConfigCoordinator.storageFilenameFormat,
            appConfigCoordinator.storageTimestampFormat,
        ) { rootDirectory, imageDirectory, voiceDirectory, filenameFormat, timestampFormat ->
            StorageSectionState(rootDirectory, imageDirectory, voiceDirectory, filenameFormat, timestampFormat)
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
            DisplaySectionState(dateFormat, timeFormat, themeMode)
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                DisplaySectionState(
                    appConfigCoordinator.dateFormat.value,
                    appConfigCoordinator.timeFormat.value,
                    appConfigCoordinator.themeMode.value,
                ),
        )

    private val lanShareState: StateFlow<LanShareSectionState> =
        combine(
            lanShareCoordinator.lanShareE2eEnabled,
            lanShareCoordinator.lanSharePairingConfigured,
            lanShareCoordinator.lanShareDeviceName,
            lanShareCoordinator.pairingCodeError,
        ) { e2eEnabled, pairingConfigured, deviceName, pairingCodeError ->
            LanShareSectionState(e2eEnabled, pairingConfigured, deviceName, pairingCodeError)
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
            ShareCardSectionState(style, showTime, showBrand)
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                ShareCardSectionState(
                    appConfigCoordinator.shareCardStyle.value,
                    appConfigCoordinator.shareCardShowTime.value,
                    appConfigCoordinator.shareCardShowBrand.value,
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
            GitIdentityState(enabled, remoteUrl, patConfigured, authorName, authorEmail)
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
            GitSyncSettingsState(autoSyncEnabled, autoSyncInterval, syncOnRefreshEnabled, lastSyncTime, syncState)
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

    private val webDavIdentityInputs: StateFlow<Triple<Boolean, WebDavProvider, String>> =
        combine(
            webDavCoordinator.webDavSyncEnabled,
            webDavCoordinator.webDavProvider,
            webDavCoordinator.webDavBaseUrl,
        ) { enabled, provider, baseUrl ->
            Triple(enabled, provider, baseUrl)
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                Triple(
                    webDavCoordinator.webDavSyncEnabled.value,
                    webDavCoordinator.webDavProvider.value,
                    webDavCoordinator.webDavBaseUrl.value,
                ),
        )

    private val webDavIdentityState: StateFlow<WebDavIdentityState> =
        combine(
            webDavIdentityInputs,
            webDavCoordinator.webDavEndpointUrl,
            webDavCoordinator.webDavUsername,
            webDavCoordinator.passwordConfigured,
        ) { baseInputs, endpointUrl, username, passwordConfigured ->
            WebDavIdentityState(
                enabled = baseInputs.first,
                provider = baseInputs.second,
                baseUrl = baseInputs.third,
                endpointUrl = endpointUrl,
                username = username,
                passwordConfigured = passwordConfigured,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                WebDavIdentityState(
                    enabled = webDavCoordinator.webDavSyncEnabled.value,
                    provider = webDavCoordinator.webDavProvider.value,
                    baseUrl = webDavCoordinator.webDavBaseUrl.value,
                    endpointUrl = webDavCoordinator.webDavEndpointUrl.value,
                    username = webDavCoordinator.webDavUsername.value,
                    passwordConfigured = webDavCoordinator.passwordConfigured.value,
                ),
        )

    private val webDavSyncSettingsState: StateFlow<WebDavSyncSettingsState> =
        combine(
            webDavCoordinator.webDavAutoSyncEnabled,
            webDavCoordinator.webDavAutoSyncInterval,
            webDavCoordinator.webDavSyncOnRefreshEnabled,
            webDavCoordinator.webDavLastSyncTime,
            webDavCoordinator.webDavSyncState,
        ) { autoSyncEnabled, autoSyncInterval, syncOnRefreshEnabled, lastSyncTime, syncState ->
            WebDavSyncSettingsState(autoSyncEnabled, autoSyncInterval, syncOnRefreshEnabled, lastSyncTime, syncState)
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                WebDavSyncSettingsState(
                    autoSyncEnabled = webDavCoordinator.webDavAutoSyncEnabled.value,
                    autoSyncInterval = webDavCoordinator.webDavAutoSyncInterval.value,
                    syncOnRefreshEnabled = webDavCoordinator.webDavSyncOnRefreshEnabled.value,
                    lastSyncTime = webDavCoordinator.webDavLastSyncTime.value,
                    syncState = webDavCoordinator.webDavSyncState.value,
                ),
        )

    private val webDavState: StateFlow<WebDavSectionState> =
        combine(
            webDavIdentityState,
            webDavSyncSettingsState,
            webDavCoordinator.connectionTestState,
        ) { identity, syncSettings, connectionTestState ->
            WebDavSectionState(
                enabled = identity.enabled,
                provider = identity.provider,
                baseUrl = identity.baseUrl,
                endpointUrl = identity.endpointUrl,
                username = identity.username,
                passwordConfigured = identity.passwordConfigured,
                autoSyncEnabled = syncSettings.autoSyncEnabled,
                autoSyncInterval = syncSettings.autoSyncInterval,
                syncOnRefreshEnabled = syncSettings.syncOnRefreshEnabled,
                lastSyncTime = syncSettings.lastSyncTime,
                syncState = syncSettings.syncState,
                connectionTestState = connectionTestState,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                WebDavSectionState(
                    enabled = webDavIdentityState.value.enabled,
                    provider = webDavIdentityState.value.provider,
                    baseUrl = webDavIdentityState.value.baseUrl,
                    endpointUrl = webDavIdentityState.value.endpointUrl,
                    username = webDavIdentityState.value.username,
                    passwordConfigured = webDavIdentityState.value.passwordConfigured,
                    autoSyncEnabled = webDavSyncSettingsState.value.autoSyncEnabled,
                    autoSyncInterval = webDavSyncSettingsState.value.autoSyncInterval,
                    syncOnRefreshEnabled = webDavSyncSettingsState.value.syncOnRefreshEnabled,
                    lastSyncTime = webDavSyncSettingsState.value.lastSyncTime,
                    syncState = webDavSyncSettingsState.value.syncState,
                    connectionTestState = webDavCoordinator.connectionTestState.value,
                ),
        )

    private val interactionState: StateFlow<InteractionSectionState> =
        combine(
            appConfigCoordinator.hapticFeedbackEnabled,
            appConfigCoordinator.showInputHints,
            appConfigCoordinator.doubleTapEditEnabled,
            appConfigCoordinator.freeTextCopyEnabled,
            appConfigCoordinator.appLockEnabled,
            appConfigCoordinator.quickSaveOnBackEnabled,
        ) { values ->
            InteractionSectionState(
                hapticEnabled = values[0],
                showInputHints = values[1],
                doubleTapEditEnabled = values[2],
                freeTextCopyEnabled = values[3],
                appLockEnabled = values[4],
                quickSaveOnBackEnabled = values[5],
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue =
                InteractionSectionState(
                    hapticEnabled = appConfigCoordinator.hapticFeedbackEnabled.value,
                    showInputHints = appConfigCoordinator.showInputHints.value,
                    doubleTapEditEnabled = appConfigCoordinator.doubleTapEditEnabled.value,
                    freeTextCopyEnabled = appConfigCoordinator.freeTextCopyEnabled.value,
                    appLockEnabled = appConfigCoordinator.appLockEnabled.value,
                    quickSaveOnBackEnabled = appConfigCoordinator.quickSaveOnBackEnabled.value,
                ),
        )

    private val systemState: StateFlow<SystemSectionState> =
        appConfigCoordinator.checkUpdatesOnStartup
            .map(::SystemSectionState)
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = SystemSectionState(appConfigCoordinator.checkUpdatesOnStartup.value),
            )

    private val coreWithoutWebDav:
        StateFlow<Pair<StorageSectionState, Pair<DisplaySectionState, Pair<LanShareSectionState, Pair<ShareCardSectionState, GitSectionState>>>>> =
        combine(storageState, displayState, lanShareState, shareCardState, gitState) { storage, display, lanShare, shareCard, git ->
            storage to (display to (lanShare to (shareCard to git)))
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = storageState.value to (displayState.value to (lanShareState.value to (shareCardState.value to gitState.value))),
        )

    private val coreUiSections: StateFlow<CoreUiSections> =
        combine(coreWithoutWebDav, webDavState) { core, webDav ->
            CoreUiSections(
                storage = core.first,
                display = core.second.first,
                lanShare = core.second.second.first,
                shareCard = core.second.second.second.first,
                git = core.second.second.second.second,
                webDav = webDav,
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
                    webDav = webDavState.value,
                ),
        )

    val uiState: StateFlow<SettingsScreenUiState> =
        combine(coreUiSections, interactionState, systemState, operationError) { core, interaction, system, operationError ->
            SettingsScreenUiState(
                storage = core.storage,
                display = core.display,
                lanShare = core.lanShare,
                shareCard = core.shareCard,
                git = core.git,
                webDav = core.webDav,
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
                    webDav = coreUiSections.value.webDav,
                    interaction = interactionState.value,
                    system = systemState.value,
                    operationError = operationError.value,
                ),
        )
}
