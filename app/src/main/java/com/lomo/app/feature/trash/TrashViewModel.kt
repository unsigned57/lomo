package com.lomo.app.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.runDeleteAnimationWithRollback
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        private val memoUiCoordinator: MemoUiCoordinator,
        private val appConfigUiCoordinator: AppConfigUiCoordinator,
        private val imageMapProvider: ImageMapProvider,
        private val memoUiMapper: MemoUiMapper,
    ) : ViewModel() {
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        private val _deletingMemoIds = MutableStateFlow<Set<String>>(emptySet())
        val deletingMemoIds: StateFlow<Set<String>> = _deletingMemoIds.asStateFlow()

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap
        val imageDirectory: StateFlow<String?> =
            appConfigUiCoordinator
                .imageDirectory()
                .stateIn(
                    viewModelScope,
                    appWhileSubscribed(),
                    null,
                )

        val trashMemos: StateFlow<List<Memo>> =
            memoUiCoordinator
                .deletedMemos()
                .stateIn(viewModelScope, appWhileSubscribed(), emptyList())

        init {
            // Keep deletion flags only for rows that still exist in current trash list.
            // This avoids alpha "bounce back" when DB removal arrives slightly later.
            trashMemos
                .onEach { memos ->
                    val existingIds = memos.asSequence().map { it.id }.toSet()
                    _deletingMemoIds.update { current -> current.intersect(existingIds) }
                }.launchIn(viewModelScope)
        }

        val appPreferences: StateFlow<AppPreferencesState> =
            appConfigUiCoordinator
                .appPreferences()
                .stateIn(viewModelScope, appWhileSubscribed(), AppPreferencesState.defaults())

        val rootDirectory: StateFlow<String?> =
            appConfigUiCoordinator
                .rootDirectory()
                .stateIn(viewModelScope, appWhileSubscribed(), null)

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val trashUiMemos: StateFlow<List<com.lomo.app.feature.main.MemoUiModel>> =
            combine(trashMemos, rootDirectory, imageDirectory, imageMap) {
                memos,
                rootDir,
                imageDir,
                currentImageMap,
                ->
                UiMemoMappingInput(
                    memos = memos,
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = currentImageMap,
                )
            }.distinctUntilChanged()
                .mapLatest { input ->
                    memoUiMapper.mapToUiModels(
                        memos = input.memos,
                        rootPath = input.rootDirectory,
                        imagePath = input.imageDirectory,
                        imageMap = input.imageMap,
                    )
                }
                .stateIn(viewModelScope, appWhileSubscribed(), emptyList())

        fun restoreMemo(memo: Memo) {
            viewModelScope.launch {
                val result =
                    runDeleteAnimationWithRollback(
                        itemId = memo.id,
                        deletingIds = _deletingMemoIds,
                    ) {
                        memoUiCoordinator.restoreMemo(memo)
                    }
                result.exceptionOrNull()?.let { throwable ->
                    _errorMessage.value = throwable.toUserMessage("Failed to restore memo")
                }
            }
        }

        fun deletePermanently(memo: Memo) {
            viewModelScope.launch {
                val result =
                    runDeleteAnimationWithRollback(
                        itemId = memo.id,
                        deletingIds = _deletingMemoIds,
                    ) {
                        memoUiCoordinator.deletePermanently(memo)
                    }
                result.exceptionOrNull()?.let { throwable ->
                    _errorMessage.value = throwable.toUserMessage("Failed to delete memo")
                }
            }
        }

        fun clearTrash() {
            viewModelScope.launch {
                val trashSnapshot = trashMemos.value
                if (trashSnapshot.isEmpty()) return@launch

                val result =
                    runDeleteAnimationWithRollback(
                        itemIds = trashSnapshot.asSequence().map { it.id }.toSet(),
                        deletingIds = _deletingMemoIds,
                    ) {
                        trashSnapshot.forEach { memo ->
                            memoUiCoordinator.deletePermanently(memo)
                        }
                    }
                result.exceptionOrNull()?.let { throwable ->
                    _errorMessage.value = throwable.toUserMessage("Failed to clear trash")
                }
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }

        private data class UiMemoMappingInput(
            val memos: List<Memo>,
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, android.net.Uri>,
        )
    }
