package com.lomo.app.feature.synccenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.model.RemoteSyncCenterFailure
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.domain.usecase.RemoteSyncCenterUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stage-5 dark Sync Center ViewModel (P5-10).
 *
 * Host-testable state machine over [RemoteSyncCenterUseCase]. Registered in
 * [com.lomo.app.di.ViewModelModule] / navigation post P5-13. Constructed manually in tests and
 * dark prototype hosts.
 *
 * On conflict selection, loads markdown/binary detail facts via domain detail ports so Compose
 * can render real artifact bodies when the use case returns them (binary never invents text).
 */
class SyncCenterViewModel(
    private val remoteSyncCenter: RemoteSyncCenterUseCase,
    private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow(initialSyncCenterState())
    val uiState: StateFlow<SyncCenterUiState> = _uiState.asStateFlow()

    fun dispatch(intent: SyncCenterIntent) {
        val result = reduceSyncCenter(_uiState.value, intent)
        _uiState.value = result.state
        result.effects.forEach { effect -> handleEffect(effect) }
    }

    private fun handleEffect(effect: SyncCenterEffect) {
        when (effect) {
            is SyncCenterEffect.LoadInitial -> loadInitial(effect.workspaceRoot)
            is SyncCenterEffect.LoadMore -> loadMore(effect.workspaceRoot, effect.cursor, effect.limit)
            is SyncCenterEffect.Resolve -> resolve(effect)
            is SyncCenterEffect.LoadConflictDetail -> loadConflictDetail(effect)
            SyncCenterEffect.RequestCancel -> {
                // Presentation shell only until shared scheduler / runner cutover (P5-13).
            }
        }
    }

    private fun loadInitial(workspaceRoot: String) {
        viewModelScope.launch {
            runCatching {
                withContext(dispatcherProvider.io) {
                    val config = remoteSyncCenter.configSummary(workspaceRoot)
                    val session = remoteSyncCenter.sessionProgress(workspaceRoot)
                    val page =
                        remoteSyncCenter.listConflicts(
                            workspaceRoot = workspaceRoot,
                            cursor = 0,
                            limit = SYNC_CENTER_CONFLICT_PAGE_LIMIT,
                        )
                    Triple(config, session, page)
                }
            }.onSuccess { (config, session, page) ->
                _uiState.update { applySyncCenterLoadSuccess(it, config, session, page) }
            }.onFailure { error ->
                _uiState.update {
                    applySyncCenterLoadFailure(it, failureMessage(error))
                }
            }
        }
    }

    private fun loadMore(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(dispatcherProvider.io) {
                    remoteSyncCenter.listConflicts(
                        workspaceRoot = workspaceRoot,
                        cursor = cursor,
                        limit = limit,
                    )
                }
            }.onSuccess { page ->
                _uiState.update { applySyncCenterPageAppend(it, page) }
            }.onFailure { error ->
                _uiState.update { current ->
                    val ready = current.load as? SyncCenterLoadState.Ready ?: return@update current
                    current.copy(load = ready.copy(lastError = failureMessage(error)))
                }
            }
        }
    }

    private fun resolve(effect: SyncCenterEffect.Resolve) {
        viewModelScope.launch {
            runCatching {
                withContext(dispatcherProvider.io) {
                    remoteSyncCenter.resolveConflicts(
                        workspaceRoot = effect.workspaceRoot,
                        expectedRevision = effect.expectedRevision,
                        resolutions = effect.resolutions,
                    )
                }
            }.onSuccess { result ->
                _uiState.update {
                    applySyncCenterResolveSuccess(
                        state = it,
                        sessionId = result.sessionId,
                        conflictRevision = result.conflictRevision,
                        appliedPaths = result.appliedPaths,
                    )
                }
            }.onFailure { error ->
                _uiState.update { applySyncCenterResolveFailure(it, failureMessage(error)) }
            }
        }
    }

    /**
     * Load detail facts for the selected conflict via domain markdown/binary ports.
     *
     * Markdown may carry real base/local/remote bodies when the use case returns them.
     * Binary stays digests/MIME/size/source only — never invents text preview.
     * Failures fail closed into Ready.lastError without inventing bodies.
     */
    private fun loadConflictDetail(effect: SyncCenterEffect.LoadConflictDetail) {
        viewModelScope.launch {
            val path: RemoteSyncConflictPath = effect.path
            runCatching {
                withContext(dispatcherProvider.io) {
                    when {
                        path.isMarkdown ->
                            DetailLoad.Markdown(
                                remoteSyncCenter.markdownConflictFacts(
                                    workspaceRoot = effect.workspaceRoot,
                                    path = path,
                                    mergedDraft = effect.mergedDraft,
                                ),
                            )
                        path.isBinary ->
                            DetailLoad.Binary(
                                remoteSyncCenter.binaryConflictFacts(
                                    workspaceRoot = effect.workspaceRoot,
                                    path = path,
                                ),
                            )
                        else -> DetailLoad.Unsupported
                    }
                }
            }.onSuccess { detail ->
                when (detail) {
                    is DetailLoad.Markdown ->
                        _uiState.update {
                            applySyncCenterMarkdownDetailSuccess(
                                state = it,
                                path = path.path,
                                facts = detail.facts,
                            )
                        }
                    is DetailLoad.Binary ->
                        _uiState.update {
                            applySyncCenterBinaryDetailSuccess(
                                state = it,
                                path = path.path,
                                facts = detail.facts,
                            )
                        }
                    DetailLoad.Unsupported ->
                        _uiState.update { current ->
                            val ready =
                                current.load as? SyncCenterLoadState.Ready
                                    ?: return@update current
                            current.copy(load = ready.copy(isLoadingDetail = false))
                        }
                }
            }.onFailure { error ->
                _uiState.update {
                    applySyncCenterDetailFailure(
                        state = it,
                        path = path.path,
                        message = failureMessage(error),
                    )
                }
            }
        }
    }

    private fun failureMessage(error: Throwable): String =
        when (error) {
            is RemoteSyncCenterFailure -> "${error.category}:${error.code}"
            else -> error.message ?: error::class.simpleName ?: "unknown_error"
        }

    private sealed interface DetailLoad {
        data class Markdown(
            val facts: com.lomo.domain.model.RemoteSyncMarkdownConflictFacts,
        ) : DetailLoad

        data class Binary(
            val facts: com.lomo.domain.model.RemoteSyncBinaryConflictFacts,
        ) : DetailLoad

        data object Unsupported : DetailLoad
    }
}
