package com.lomo.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.usecase.GitRemoteUrlUseCase
import com.lomo.domain.usecase.GitSyncErrorUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        appConfigRepository: AppConfigRepository,
        shareServiceManager: LanShareService,
        gitSyncRepo: GitSyncRepository,
        syncPolicyRepository: SyncPolicyRepository,
        switchRootStorageUseCase: SwitchRootStorageUseCase,
        syncAndRebuildUseCase: SyncAndRebuildUseCase,
        gitRemoteUrlUseCase: GitRemoteUrlUseCase,
        gitSyncErrorUseCase: GitSyncErrorUseCase,
    ) : ViewModel() {
        private val appConfigCoordinator =
            SettingsAppConfigCoordinator(
                appConfigRepository = appConfigRepository,
                switchRootStorageUseCase = switchRootStorageUseCase,
                scope = viewModelScope,
            )

        private val lanShareCoordinator =
            SettingsLanShareCoordinator(
                shareServiceManager = shareServiceManager,
                scope = viewModelScope,
            )

        private val gitCoordinator =
            SettingsGitCoordinator(
                gitSyncRepo = gitSyncRepo,
                syncPolicyRepository = syncPolicyRepository,
                syncAndRebuildUseCase = syncAndRebuildUseCase,
                gitRemoteUrlUseCase = gitRemoteUrlUseCase,
                gitSyncErrorUseCase = gitSyncErrorUseCase,
                scope = viewModelScope,
            )

        private val _operationError = MutableStateFlow<String?>(null)
        private val operationErrorFlow = _operationError.asStateFlow()
        private val errorMapper = SettingsOperationErrorMapper(gitSyncErrorUseCase)
        private val actionCoordinator =
            SettingsActionCoordinator(
                scope = viewModelScope,
                lanShareCoordinator = lanShareCoordinator,
                gitCoordinator = gitCoordinator,
                errorMapper = errorMapper,
            ) { message ->
                _operationError.value = message
            }
        private val stateProvider =
            SettingsStateProvider(
                appConfigCoordinator = appConfigCoordinator,
                lanShareCoordinator = lanShareCoordinator,
                gitCoordinator = gitCoordinator,
                operationError = operationErrorFlow,
                scope = viewModelScope,
            )

        val uiState: StateFlow<SettingsScreenUiState> = stateProvider.uiState

        val pairingCodeError: StateFlow<String?> = stateProvider.pairingCodeError
        val connectionTestState: StateFlow<SettingsGitConnectionTestState> = stateProvider.connectionTestState
        val operationError: StateFlow<String?> = stateProvider.operationError

        val storageFeature =
            SettingsStorageFeatureViewModel(
                scope = viewModelScope,
                appConfigCoordinator = appConfigCoordinator,
            )
        val displayFeature =
            SettingsDisplayFeatureViewModel(
                scope = viewModelScope,
                appConfigCoordinator = appConfigCoordinator,
            )
        val shareCardFeature =
            SettingsShareCardFeatureViewModel(
                scope = viewModelScope,
                appConfigCoordinator = appConfigCoordinator,
            )
        val interactionFeature =
            SettingsInteractionFeatureViewModel(
                scope = viewModelScope,
                appConfigCoordinator = appConfigCoordinator,
            )
        val systemFeature =
            SettingsSystemFeatureViewModel(
                scope = viewModelScope,
                appConfigCoordinator = appConfigCoordinator,
            )
        val lanShareFeature =
            SettingsLanShareFeatureViewModel(
                actionCoordinator = actionCoordinator,
                lanShareCoordinator = lanShareCoordinator,
            )
        val gitFeature =
            SettingsGitFeatureViewModel(
                actionCoordinator = actionCoordinator,
                gitCoordinator = gitCoordinator,
            )

        init {
            actionCoordinator.refreshPatConfigured()
        }

        fun clearOperationError() {
            _operationError.value = null
        }
    }
