package com.lomo.app.feature.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.usecase.BackupSyncConflictFilesUseCase
import com.lomo.domain.usecase.SyncConflictResolutionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncConflictViewModel
    @Inject
    constructor(
        private val syncConflictResolutionUseCase: SyncConflictResolutionUseCase,
        private val backupSyncConflictFilesUseCase: BackupSyncConflictFilesUseCase,
    ) : ViewModel() {
        private val _state = MutableStateFlow<SyncConflictDialogState>(SyncConflictDialogState.Hidden)
        val state: StateFlow<SyncConflictDialogState> = _state.asStateFlow()

        fun showConflictDialog(conflictSet: SyncConflictSet) {
            _state.value =
                SyncConflictDialogState.Showing(
                    conflictSet = conflictSet,
                    perFileChoices = emptyMap(),
                    expandedFilePath = null,
                    isResolving = false,
                )
        }

        fun dismiss() {
            _state.value = SyncConflictDialogState.Hidden
        }

        fun setFileChoice(
            path: String,
            choice: SyncConflictResolutionChoice,
        ) {
            _state.update { current ->
                if (current is SyncConflictDialogState.Showing) {
                    current.copy(perFileChoices = current.perFileChoices + (path to choice))
                } else {
                    current
                }
            }
        }

        fun setAllChoices(choice: SyncConflictResolutionChoice) {
            _state.update { current ->
                if (current is SyncConflictDialogState.Showing) {
                    val allChoices = current.conflictSet.files.associate { it.relativePath to choice }
                    current.copy(perFileChoices = allChoices)
                } else {
                    current
                }
            }
        }

        fun toggleExpandedFile(path: String) {
            _state.update { current ->
                if (current is SyncConflictDialogState.Showing) {
                    current.copy(
                        expandedFilePath = if (current.expandedFilePath == path) null else path,
                    )
                } else {
                    current
                }
            }
        }

        fun applyResolution() {
            val current = _state.value
            if (current !is SyncConflictDialogState.Showing || current.isResolving) return

            _state.value = current.copy(isResolving = true)

            viewModelScope.launch {
                runCatching {
                    backupSyncConflictFilesUseCase(
                        files = current.conflictSet.files,
                        localFileReader = { null },
                    )
                    syncConflictResolutionUseCase.resolve(
                        conflictSet = current.conflictSet,
                        resolution = SyncConflictResolution(current.perFileChoices),
                    )
                    _state.value = SyncConflictDialogState.Hidden
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    _state.update { state ->
                        if (state is SyncConflictDialogState.Showing) {
                            state.copy(isResolving = false)
                        } else {
                            state
                        }
                    }
                }
            }
        }
    }
