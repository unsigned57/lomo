package com.lomo.app.feature.settings

import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.repository.CustomFontStore
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.usecase.GitSyncSettingsUseCase
import com.lomo.domain.usecase.S3SyncSettingsUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.WebDavSyncSettingsUseCase
import kotlinx.coroutines.CoroutineScope


/** Collaborators shared by every settings coordinator this factory creates. */
data class SettingsCoordinatorDependencies(
    val appConfigRepository: AppConfigRepository,
    val credentialRepository: CredentialRepository,
    val lanShareService: LanShareService,
    val gitSyncSettingsUseCase: GitSyncSettingsUseCase,
    val webDavSyncSettingsUseCase: WebDavSyncSettingsUseCase,
    val s3SyncSettingsUseCase: S3SyncSettingsUseCase,
    val switchRootStorageUseCase: SwitchRootStorageUseCase,
    val memoSnapshotPreferencesRepository: MemoSnapshotPreferencesRepository,
    val customFontStore: CustomFontStore,
    val engineReadinessRepository: EngineReadinessRepository,
    val syncInboxRepository: SyncInboxRepository? = null,
)

class SettingsCoordinatorFactory(
    dependencies: SettingsCoordinatorDependencies,
) {
    private val appConfigRepository = dependencies.appConfigRepository
    private val credentialRepository = dependencies.credentialRepository
    private val lanShareService = dependencies.lanShareService
    private val gitSyncSettingsUseCase = dependencies.gitSyncSettingsUseCase
    private val webDavSyncSettingsUseCase = dependencies.webDavSyncSettingsUseCase
    private val s3SyncSettingsUseCase = dependencies.s3SyncSettingsUseCase
    private val switchRootStorageUseCase = dependencies.switchRootStorageUseCase
    private val memoSnapshotPreferencesRepository = dependencies.memoSnapshotPreferencesRepository
    private val customFontStore = dependencies.customFontStore
    private val engineReadinessRepository = dependencies.engineReadinessRepository
    private val syncInboxRepository = dependencies.syncInboxRepository
        private val settingsCredentialCoordinator =
            SettingsCredentialCoordinator(credentialRepository)

        fun createAppConfigCoordinator(scope: CoroutineScope): SettingsAppConfigCoordinator =
            SettingsAppConfigCoordinator(
                appConfigRepository = appConfigRepository,
                switchRootStorageUseCase = switchRootStorageUseCase,
                scope = scope,
                customFontStore = customFontStore,
                memoSnapshotPreferencesRepository = memoSnapshotPreferencesRepository,
                syncInboxRepository = syncInboxRepository,
            )

        fun createLanShareCoordinator(scope: CoroutineScope): SettingsLanShareCoordinator =
            SettingsLanShareCoordinator(
                shareServiceManager = lanShareService,
                scope = scope,
            )

        fun createGitCoordinator(scope: CoroutineScope): SettingsGitCoordinator =
            SettingsGitCoordinator(
                gitSyncSettingsUseCase = gitSyncSettingsUseCase,
                credentialCoordinator = settingsCredentialCoordinator,
                scope = scope,
            )

        fun createWebDavCoordinator(scope: CoroutineScope): SettingsWebDavCoordinator =
            SettingsWebDavCoordinator(
                webDavSyncSettingsUseCase = webDavSyncSettingsUseCase,
                credentialCoordinator = settingsCredentialCoordinator,
                scope = scope,
            )

        fun createS3Coordinator(scope: CoroutineScope): SettingsS3Coordinator =
            SettingsS3Coordinator(
                s3SyncSettingsUseCase = s3SyncSettingsUseCase,
                credentialRepository = credentialRepository,
                scope = scope,
            )

        fun createErrorMapper(): SettingsOperationErrorMapper = SettingsOperationErrorMapper()

        fun customFontStore(): CustomFontStore = customFontStore

        fun mount() = engineReadinessRepository.mount
    }
