package com.lomo.app.feature.settings

import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoVersionRepository
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
        private val lanShareService: LanShareService,
        private val gitSyncSettingsUseCase: GitSyncSettingsUseCase,
        private val webDavSyncSettingsUseCase: WebDavSyncSettingsUseCase,
        private val s3SyncSettingsUseCase: S3SyncSettingsUseCase,
        private val switchRootStorageUseCase: SwitchRootStorageUseCase,
        private val memoSnapshotPreferencesRepository: MemoSnapshotPreferencesRepository,
        private val memoVersionRepository: MemoVersionRepository,
    ) {
        fun createAppConfigCoordinator(scope: CoroutineScope): SettingsAppConfigCoordinator =
            SettingsAppConfigCoordinator(
                appConfigRepository = appConfigRepository,
                switchRootStorageUseCase = switchRootStorageUseCase,
                scope = scope,
                memoSnapshotPreferencesRepository = memoSnapshotPreferencesRepository,
                memoVersionRepository = memoVersionRepository,
            )

        fun createLanShareCoordinator(scope: CoroutineScope): SettingsLanShareCoordinator =
            SettingsLanShareCoordinator(
                shareServiceManager = lanShareService,
                scope = scope,
            )

        fun createGitCoordinator(scope: CoroutineScope): SettingsGitCoordinator =
            SettingsGitCoordinator(
                gitSyncSettingsUseCase = gitSyncSettingsUseCase,
                scope = scope,
            )

        fun createWebDavCoordinator(scope: CoroutineScope): SettingsWebDavCoordinator =
            SettingsWebDavCoordinator(
                webDavSyncSettingsUseCase = webDavSyncSettingsUseCase,
                scope = scope,
            )

        fun createS3Coordinator(scope: CoroutineScope): SettingsS3Coordinator =
            SettingsS3Coordinator(
                s3SyncSettingsUseCase = s3SyncSettingsUseCase,
                scope = scope,
            )

        fun createErrorMapper(): SettingsOperationErrorMapper = SettingsOperationErrorMapper()
    }
