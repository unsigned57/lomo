package com.lomo.app.feature.settings

import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncState
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

    val pairingCodeError: StateFlow<String?> = lanShareCoordinator.pairingCodeError
    val connectionTestState: StateFlow<SettingsGitConnectionTestState> = gitCoordinator.connectionTestState
    val webDavConnectionTestState: StateFlow<SettingsWebDavConnectionTestState> = webDavCoordinator.connectionTestState
    private val s3StateProvider = SettingsS3StateProvider(s3Coordinator = s3Coordinator, scope = scope)
    val s3ConnectionTestState: StateFlow<SettingsS3ConnectionTestState> = s3StateProvider.connectionTestState

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
            started = settingsWhileSubscribed(),
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
            started = settingsWhileSubscribed(),
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
            started = settingsWhileSubscribed(),
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
            appConfigCoordinator.shareCardShowTime,
            appConfigCoordinator.shareCardShowBrand,
        ) { showTime, showBrand ->
            ShareCardSectionState(showTime, showBrand)
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                ShareCardSectionState(
                    appConfigCoordinator.shareCardShowTime.value,
                    appConfigCoordinator.shareCardShowBrand.value,
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
            started = settingsWhileSubscribed(),
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
            started = settingsWhileSubscribed(),
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
            started = settingsWhileSubscribed(),
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
            started = settingsWhileSubscribed(),
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
            started = settingsWhileSubscribed(),
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
            started = settingsWhileSubscribed(),
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
            started = settingsWhileSubscribed(),
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

    private val s3State: StateFlow<S3SectionState> = s3StateProvider.sectionState

    private val interactionState: StateFlow<InteractionSectionState> =
        combine(
            appConfigCoordinator.hapticFeedbackEnabled,
            appConfigCoordinator.showInputHints,
            appConfigCoordinator.doubleTapEditEnabled,
            appConfigCoordinator.freeTextCopyEnabled,
            appConfigCoordinator.memoActionAutoReorderEnabled,
            appConfigCoordinator.appLockEnabled,
            appConfigCoordinator.quickSaveOnBackEnabled,
        ) { values ->
            InteractionSectionState(
                hapticEnabled = values[0],
                showInputHints = values[1],
                doubleTapEditEnabled = values[2],
                freeTextCopyEnabled = values[3],
                memoActionAutoReorderEnabled = values[4],
                appLockEnabled = values[5],
                quickSaveOnBackEnabled = values[6],
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
