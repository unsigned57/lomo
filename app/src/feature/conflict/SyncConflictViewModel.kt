package com.lomo.app.feature.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.usecase.BackupSyncConflictFilesUseCase
import com.lomo.domain.usecase.RemoteSyncConflictDialogUseCase
import com.lomo.domain.usecase.SyncReviewResolutionUseCase
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Original conflict/review dialog ViewModel.
 *
 * Remote conflicts resolve only through [RemoteSyncConflictDialogUseCase] (Rust expected-revision).
 * Review (Sync Inbox) remains on [SyncReviewResolutionUseCase].
 */
class SyncConflictViewModel(
    private val remoteSyncConflictDialogUseCase: RemoteSyncConflictDialogUseCase,
    private val syncReviewResolutionUseCase: SyncReviewResolutionUseCase,
    private val backupSyncConflictFilesUseCase: BackupSyncConflictFilesUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow<SyncConflictDialogState>(SyncConflictDialogState.Hidden)
    val state: StateFlow<SyncConflictDialogState> = _state.asStateFlow()

    private var openRemoteSession: RemoteSyncConflictDialogUseCase.OpenSession? = null

    /**
     * Open dialog from a Rust-backed open session (preferred production path).
     */
    fun showRemoteConflictSession(session: RemoteSyncConflictDialogUseCase.OpenSession) {
        openRemoteSession = session
        _state.value =
            SyncConflictDialogState.Showing(
                conflictSet = session.conflictSet,
                perFileChoices = buildSuggestedChoices(session.conflictSet),
                expandedFilePath = null,
                isResolving = false,
            )
    }

    /**
     * Open dialog with a presentation [SyncConflictSet] only when a remote session is already
     * bound (or for review-less host tests that inject resolve via session later). Prefer
     * [showRemoteConflictSession].
     */
    fun showConflictDialog(conflictSet: SyncConflictSet) {
        // Preserve presentation-only show for tests/legacy event payloads that carry bodies.
        // Resolve still requires [openRemoteSession]; without it apply fails closed to non-resolving.
        _state.value =
            SyncConflictDialogState.Showing(
                conflictSet = conflictSet,
                perFileChoices = buildSuggestedChoices(conflictSet),
                expandedFilePath = null,
                isResolving = false,
            )
    }

    fun showReviewDialog(review: SyncReviewSession) {
        openRemoteSession = null
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
        openRemoteSession = null
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
                        val session = openRemoteSession
                        if (session == null) {
                            // Fail closed: no dual-stack Kotlin engine resolve without Rust session.
                            _state.value = current.copy(isResolving = false)
                            return@runCatching
                        }
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
                            resolveRemoteConflictDialogState(
                                current = current,
                                session = session,
                                useCase = remoteSyncConflictDialogUseCase,
                                onSessionUpdated = { openRemoteSession = it },
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

/** Safe auto-resolve entry; lives outside the ViewModel class for TooManyFunctions budget. */
fun SyncConflictViewModel.autoResolveSafeConflicts() {
    when (val current = state.value) {
        is SyncConflictDialogState.Showing ->
            current.safeAutoResolveChoices()?.let { choices ->
                choices.forEach { (path, choice) -> setFileChoice(path, choice) }
                applyResolution()
            }

        is SyncConflictDialogState.ReviewShowing ->
            current.safeAutoResolveChoices()?.let { choices ->
                choices.forEach { (path, choice) -> setReviewItemChoice(path, choice) }
                applyResolution()
            }

        SyncConflictDialogState.Hidden -> Unit
    }
}

internal suspend fun resolveRemoteConflictDialogState(
    current: SyncConflictDialogState.Showing,
    session: RemoteSyncConflictDialogUseCase.OpenSession,
    useCase: RemoteSyncConflictDialogUseCase,
    onSessionUpdated: (RemoteSyncConflictDialogUseCase.OpenSession?) -> Unit,
): SyncConflictDialogState =
    when (
        val result =
            useCase.resolveSuspending(
                session = session,
                resolution = SyncConflictResolution(current.perFileChoices),
            )
    ) {
        RemoteSyncConflictDialogUseCase.DialogResolveResult.Resolved -> {
            onSessionUpdated(null)
            SyncConflictDialogState.Hidden
        }

        is RemoteSyncConflictDialogUseCase.DialogResolveResult.Pending -> {
            onSessionUpdated(result.session)
            pendingConflictState(current, result.session.conflictSet)
        }
    }
