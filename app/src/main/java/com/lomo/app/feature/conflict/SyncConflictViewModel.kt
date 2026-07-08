package com.lomo.app.feature.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.usecase.BackupSyncConflictFilesUseCase
import com.lomo.domain.usecase.SyncConflictResolutionUseCase
import com.lomo.domain.usecase.SyncReviewResolutionUseCase

import kotlinx.coroutines.CancellationException
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


class SyncConflictViewModel(
    private val syncConflictResolutionUseCase: SyncConflictResolutionUseCase,
    private val syncReviewResolutionUseCase: SyncReviewResolutionUseCase,
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

        fun showReviewDialog(review: SyncReviewSession) {
            val blockedPaths = review.blockedPaths()
            _state.value =
                SyncConflictDialogState.ReviewShowing(
                    reviewSession = review,
                    perItemChoices = buildReviewSuggestedChoices(review, blockedPaths),
                    blockedPaths = blockedPaths,
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

        fun setReviewItemChoice(
            path: String,
            choice: SyncReviewResolutionChoice,
        ) {
            _state.update { current ->
                if (current is SyncConflictDialogState.ReviewShowing && path !in current.blockedPaths) {
                    current.copy(perItemChoices = (current.perItemChoices + (path to choice)).toImmutableMap())
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

        fun setAllReviewItemChoices(choice: SyncReviewResolutionChoice) {
            _state.update { current ->
                if (current is SyncConflictDialogState.ReviewShowing) {
                    val allChoices =
                        current.reviewSession.items
                            .filterNot { it.relativePath in current.blockedPaths }
                            .associate { it.relativePath to choice }
                            .toImmutableMap()
                    current.copy(perItemChoices = allChoices)
                } else {
                    current
                }
            }
        }

        fun acceptSuggestedChoices() {
            _state.update { current ->
                when (current) {
                    is SyncConflictDialogState.Showing ->
                        current.copy(
                            perFileChoices =
                                (current.perFileChoices + buildSuggestedChoices(current.conflictSet)).toImmutableMap(),
                        )

                    is SyncConflictDialogState.ReviewShowing ->
                        current.copy(
                            perItemChoices =
                                (
                                    current.perItemChoices +
                                        buildReviewSuggestedChoices(current.reviewSession, current.blockedPaths)
                                ).toImmutableMap(),
                        )

                    SyncConflictDialogState.Hidden -> current
                }
            }
        }

        fun autoResolveSafeConflicts() {
            val current = _state.value
            when (current) {
                is SyncConflictDialogState.Showing ->
                    current.safeAutoResolveChoices()?.let { choices ->
                        _state.value = current.copy(perFileChoices = choices)
                        applyResolution()
                    }

                is SyncConflictDialogState.ReviewShowing ->
                    current.safeAutoResolveChoices()?.let { choices ->
                        _state.value = current.copy(perItemChoices = choices)
                        applyResolution()
                    }

                SyncConflictDialogState.Hidden -> Unit
            }
        }

        fun toggleExpandedFile(path: String) {
            _state.update { current ->
                when (current) {
                    is SyncConflictDialogState.Showing ->
                        current.copy(expandedFilePath = if (current.expandedFilePath == path) null else path)

                    is SyncConflictDialogState.ReviewShowing ->
                        current.copy(expandedFilePath = if (current.expandedFilePath == path) null else path)

                    SyncConflictDialogState.Hidden -> current
                }
            }
        }

        fun applyResolution() {
            val current = _state.value
            if (current == SyncConflictDialogState.Hidden || current.isResolving()) return

            _state.value = current.withResolving(true)

            viewModelScope.launch {
                runCatching {
                    when (current) {
                        is SyncConflictDialogState.Showing -> {
                            val filesToBackup =
                                current.conflictSet.files.filter { file ->
                                    current.perFileChoices[file.relativePath] !=
                                        SyncConflictResolutionChoice.SKIP_FOR_NOW
                                }
                            backupSyncConflictFilesUseCase(
                                files = filesToBackup,
                                localFileReader = { null },
                            )
                            _state.value =
                                resolveConflictDialogState(
                                    current = current,
                                    useCase = syncConflictResolutionUseCase,
                                )
                        }

                        is SyncConflictDialogState.ReviewShowing ->
                            _state.value =
                                resolveReviewDialogState(
                                    current = current,
                                    useCase = syncReviewResolutionUseCase,
                                )

                        SyncConflictDialogState.Hidden -> Unit
                    }
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    _state.update { state ->
                        when (state) {
                            is SyncConflictDialogState.Showing -> state.copy(isResolving = false)
                            is SyncConflictDialogState.ReviewShowing -> state.copy(isResolving = false)
                            SyncConflictDialogState.Hidden -> state
                        }
                    }
                }
            }
        }
    }
