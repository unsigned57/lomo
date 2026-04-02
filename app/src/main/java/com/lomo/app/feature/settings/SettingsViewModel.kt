package com.lomo.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.update.AppUpdateChecker
import com.lomo.domain.usecase.GetCurrentAppVersionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        coordinatorFactory: SettingsCoordinatorFactory,
        appUpdateChecker: AppUpdateChecker? = null,
        getCurrentAppVersionUseCase: GetCurrentAppVersionUseCase? = null,
    ) : ViewModel() {
        private val appConfigCoordinator =
            coordinatorFactory.createAppConfigCoordinator(viewModelScope)

        private val lanShareCoordinator =
            coordinatorFactory.createLanShareCoordinator(viewModelScope)

        private val gitCoordinator =
            coordinatorFactory.createGitCoordinator(viewModelScope)

        private val webDavCoordinator =
            coordinatorFactory.createWebDavCoordinator(viewModelScope)

        private val s3Coordinator =
            coordinatorFactory.createS3Coordinator(viewModelScope)

        private val _operationError = MutableStateFlow<SettingsOperationError?>(null)
        private val operationErrorFlow = _operationError.asStateFlow()
        private val errorMapper = coordinatorFactory.createErrorMapper()
        private val actionCoordinator =
            SettingsActionCoordinator(
                scope = viewModelScope,
                lanShareCoordinator = lanShareCoordinator,
                gitCoordinator = gitCoordinator,
                webDavCoordinator = webDavCoordinator,
                s3Coordinator = s3Coordinator,
                errorMapper = errorMapper,
            ) { message ->
                _operationError.value = message
            }
        private val stateProvider =
            SettingsStateProvider(
                appConfigCoordinator = appConfigCoordinator,
                lanShareCoordinator = lanShareCoordinator,
                gitCoordinator = gitCoordinator,
                webDavCoordinator = webDavCoordinator,
                s3Coordinator = s3Coordinator,
                operationError = operationErrorFlow,
                scope = viewModelScope,
            )

        val uiState: StateFlow<SettingsScreenUiState> = stateProvider.uiState

        val pairingCodeError: StateFlow<String?> = stateProvider.pairingCodeError
        val connectionTestState: StateFlow<SettingsGitConnectionTestState> = stateProvider.connectionTestState
        val operationError: StateFlow<SettingsOperationError?> = stateProvider.operationError

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
        val snapshotFeature =
            SettingsSnapshotFeatureViewModel(
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
                appUpdateChecker = appUpdateChecker,
                getCurrentAppVersionUseCase = getCurrentAppVersionUseCase,
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
        val webDavFeature =
            SettingsWebDavFeatureViewModel(
                actionCoordinator = actionCoordinator,
                webDavCoordinator = webDavCoordinator,
            )
        val s3Feature =
            SettingsS3FeatureViewModel(
                actionCoordinator = actionCoordinator,
                s3Coordinator = s3Coordinator,
            )

        init {
            actionCoordinator.refreshPatConfigured()
            actionCoordinator.refreshWebDavPasswordConfigured()
            actionCoordinator.refreshS3CredentialConfigured()
        }

        fun clearOperationError() {
            _operationError.value = null
        }
    }
