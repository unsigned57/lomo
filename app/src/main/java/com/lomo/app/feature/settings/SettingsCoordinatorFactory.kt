package com.lomo.app.feature.settings

import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.usecase.GitSyncSettingsUseCase
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
        private val switchRootStorageUseCase: SwitchRootStorageUseCase,
    ) {
        fun createAppConfigCoordinator(scope: CoroutineScope): SettingsAppConfigCoordinator =
            SettingsAppConfigCoordinator(
                appConfigRepository = appConfigRepository,
                switchRootStorageUseCase = switchRootStorageUseCase,
                scope = scope,
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

        fun createErrorMapper(): SettingsOperationErrorMapper = SettingsOperationErrorMapper()
    }
