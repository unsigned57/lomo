package com.lomo.app.feature.tag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.runDeleteAnimationWithRollback
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.main.mapToUiModelState
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.ui.component.menu.MemoActionId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagFilterViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val memoUiCoordinator: MemoUiCoordinator,
        private val appConfigStateProvider: AppConfigStateProvider,
        private val appConfigUiCoordinator: AppConfigUiCoordinator,
        private val imageMapProvider: ImageMapProvider,
        private val memoUiMapper: MemoUiMapper,
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoContentUseCase: UpdateMemoContentUseCase,
        private val toggleMemoCheckboxUseCase: ToggleMemoCheckboxUseCase,
        private val saveImageUseCase: SaveImageUseCase,
    ) : ViewModel() {
        val tagName: String = savedStateHandle.get<String>("tagName") ?: ""
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
        private val _deletingMemoIds = MutableStateFlow<Set<String>>(emptySet())
        val deletingMemoIds: StateFlow<Set<String>> = _deletingMemoIds.asStateFlow()

        private val rootDirectory: StateFlow<String?> = appConfigStateProvider.rootDirectory

        private val imageDirectory: StateFlow<String?> = appConfigStateProvider.imageDirectory

        val appPreferences: StateFlow<AppPreferencesState> = appConfigStateProvider.appPreferences

        val activeDayCount: StateFlow<Int> =
            memoUiCoordinator
                .activeDayCount()
                .stateIn(viewModelScope, appWhileSubscribed(), 0)

        val rootDir: StateFlow<String?> = rootDirectory
        val imageDir: StateFlow<String?> = imageDirectory
        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        val memos: StateFlow<List<Memo>> =
            memoUiCoordinator
                .memosByTag(tagName)
                .stateIn(viewModelScope, appWhileSubscribed(), emptyList())

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val uiMemos: StateFlow<List<com.lomo.app.feature.main.MemoUiModel>> =
            memos.mapToUiModelState(
                rootDirectory = rootDir,
                imageDirectory = imageDir,
                imageMap = imageMap,
                memoUiMapper = memoUiMapper,
                scope = viewModelScope,
            )

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                val result =
                    runDeleteAnimationWithRollback(
                        itemId = memo.id,
                        deletingIds = _deletingMemoIds,
                    ) {
                        deleteMemoUseCase(memo)
                    }
                result.exceptionOrNull()?.let { throwable ->
                    _errorMessage.value = throwable.toUserMessage("Failed to delete memo")
                }
            }
        }

        fun onDeleteAnimationSettled(memoId: String) {
            _deletingMemoIds.value = _deletingMemoIds.value - memoId
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                runCatching {
                    updateMemoContentUseCase(memo, newContent)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to update memo")
                }
            }
        }

        fun toggleTodo(
            memo: Memo,
            lineIndex: Int,
            checked: Boolean,
        ) {
            viewModelScope.launch {
                runCatching {
                    toggleMemoCheckboxUseCase(memo = memo, lineIndex = lineIndex, checked = checked)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to update todo")
                }
            }
        }

        fun saveImage(
            uri: android.net.Uri,
            onResult: (String) -> Unit,
            onError: (() -> Unit)? = null,
        ) {
            viewModelScope.launch {
                runCatching {
                    val path =
                        when (
                            val result =
                                saveImageUseCase.saveWithCacheSyncStatus(
                                    StorageLocation(uri.toString()),
                                )
                        ) {
                            is SaveImageResult.SavedAndCacheSynced -> result.location.raw
                            is SaveImageResult.SavedButCacheSyncFailed -> throw result.cause
                        }
                    onResult(path)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to save image")
                    onError?.invoke()
                }
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }

        fun recordMemoActionUsage(actionId: MemoActionId) {
            viewModelScope.launch {
                appConfigUiCoordinator.recordMemoActionUsage(
                    scope = MemoActionOrderScopes.TAG,
                    actionId = actionId.storageKey,
                )
            }
        }

        val updateMemoActionOrder: (List<MemoActionId>) -> Unit = { actionIds ->
            viewModelScope.launch {
                appConfigUiCoordinator.updateMemoActionOrder(
                    scope = MemoActionOrderScopes.TAG,
                    order = actionIds.map(MemoActionId::storageKey),
                )
            }
        }

        val updateInputToolbarToolOrder: (List<String>) -> Unit = { toolIds ->
            viewModelScope.launch {
                appConfigUiCoordinator.updateInputToolbarToolOrder(toolIds)
            }
        }

    }
