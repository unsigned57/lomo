package com.lomo.app.feature.settings

import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.repository.CustomFontStore
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.usecase.GitSyncSettingsUseCase
import com.lomo.domain.usecase.S3SyncSettingsUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.WebDavSyncSettingsUseCase
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

class SettingsCoordinatorFactory
    @Inject
    constructor(
        private val appConfigRepository: AppConfigRepository,
        private val credentialRepository: CredentialRepository,
        private val lanShareService: LanShareService,
        private val gitSyncSettingsUseCase: GitSyncSettingsUseCase,
        private val webDavSyncSettingsUseCase: WebDavSyncSettingsUseCase,
        private val s3SyncSettingsUseCase: S3SyncSettingsUseCase,
        private val switchRootStorageUseCase: SwitchRootStorageUseCase,
        private val memoSnapshotPreferencesRepository: MemoSnapshotPreferencesRepository,
        private val memoVersionRepository: MemoVersionRepository,
        private val customFontStore: CustomFontStore,
        private val syncInboxRepository: SyncInboxRepository? = null,
    ) {
        private val settingsCredentialCoordinator =
            SettingsCredentialCoordinator(credentialRepository)

        fun createAppConfigCoordinator(scope: CoroutineScope): SettingsAppConfigCoordinator =
            SettingsAppConfigCoordinator(
                appConfigRepository = appConfigRepository,
                switchRootStorageUseCase = switchRootStorageUseCase,
                scope = scope,
                customFontStore = customFontStore,
                memoSnapshotPreferencesRepository = memoSnapshotPreferencesRepository,
                memoVersionRepository = memoVersionRepository,
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
    }
