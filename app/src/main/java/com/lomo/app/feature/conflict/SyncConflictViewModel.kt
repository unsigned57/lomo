package com.lomo.app.feature.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictTextMerge
import com.lomo.domain.usecase.BackupSyncConflictFilesUseCase
import com.lomo.domain.usecase.SyncConflictResolutionResult
import com.lomo.domain.usecase.SyncConflictResolutionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.toImmutableMap
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
                    perFileChoices = buildSuggestedChoices(conflictSet),
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
                    current.copy(perFileChoices = (current.perFileChoices + (path to choice)).toImmutableMap())
                } else {
                    current
                }
            }
        }

        fun setAllChoices(choice: SyncConflictResolutionChoice) {
            _state.update { current ->
                if (current is SyncConflictDialogState.Showing) {
                    val allChoices =
                        current.conflictSet.files
                            .associate { it.relativePath to choice }
                            .toImmutableMap()
                    current.copy(perFileChoices = allChoices)
                } else {
                    current
                }
            }
        }

        fun acceptSuggestedChoices() {
            _state.update { current ->
                if (current is SyncConflictDialogState.Showing) {
                    current.copy(
                        perFileChoices =
                            (current.perFileChoices + buildSuggestedChoices(current.conflictSet)).toImmutableMap(),
                    )
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
                    val filesToBackup =
                        current.conflictSet.files.filter { file ->
                            current.perFileChoices[file.relativePath] != SyncConflictResolutionChoice.SKIP_FOR_NOW
                        }
                    backupSyncConflictFilesUseCase(
                        files = filesToBackup,
                        localFileReader = { null },
                    )
                    when (
                        val result =
                            syncConflictResolutionUseCase.resolve(
                        conflictSet = current.conflictSet,
                        resolution = SyncConflictResolution(current.perFileChoices),
                    )
                    ) {
                        SyncConflictResolutionResult.Resolved -> {
                            _state.value = SyncConflictDialogState.Hidden
                        }

                        is SyncConflictResolutionResult.Pending -> {
                            val remainingChoices =
                                current.perFileChoices
                                    .filterKeys { path ->
                                        result.conflictSet.files.any { file -> file.relativePath == path }
                                    }.toImmutableMap()
                            _state.value =
                                SyncConflictDialogState.Showing(
                                    conflictSet = result.conflictSet,
                                    perFileChoices =
                                        (buildSuggestedChoices(result.conflictSet) + remainingChoices).toImmutableMap(),
                                    expandedFilePath = null,
                                    isResolving = false,
                                )
                        }
                    }
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

        private fun buildSuggestedChoices(
            conflictSet: SyncConflictSet,
        ): ImmutableMap<String, SyncConflictResolutionChoice> {
            if (conflictSet.source != SyncBackendType.S3 && conflictSet.source != SyncBackendType.WEBDAV) {
                return persistentHashMapOf()
            }
            return conflictSet.files.mapNotNull { file ->
                suggestedChoiceFor(file)?.let { choice -> file.relativePath to choice }
            }.toMap().toImmutableMap()
        }

        private fun suggestedChoiceFor(
            file: com.lomo.domain.model.SyncConflictFile,
        ): SyncConflictResolutionChoice? {
            if (file.isBinary) return null
            val localContent = file.localContent?.trim().orEmpty()
            val remoteContent = file.remoteContent?.trim().orEmpty()
            if (localContent.isBlank() || remoteContent.isBlank()) return null
            return when {
                remoteContent == localContent -> SyncConflictResolutionChoice.KEEP_REMOTE
                remoteContent.contains(localContent) -> SyncConflictResolutionChoice.KEEP_REMOTE
                localContent.contains(remoteContent) -> SyncConflictResolutionChoice.KEEP_LOCAL
                SyncConflictTextMerge.merge(localContent, remoteContent) != null ->
                    SyncConflictResolutionChoice.MERGE_TEXT
                else -> null
            }
        }
    }
