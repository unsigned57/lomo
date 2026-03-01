package com.lomo.app.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.runDeleteAnimationWithRollback
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.appPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
        private val repository: MemoRepository,
        private val appConfigRepository: AppConfigRepository,
        private val imageMapProvider: ImageMapProvider,
        private val memoUiMapper: MemoUiMapper,
    ) : ViewModel() {
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        private val deletingMemoIds = MutableStateFlow<Set<String>>(emptySet())

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap
        val imageDirectory: StateFlow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.IMAGE)
                .map { it?.raw }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    null,
                )

        val trashMemos: StateFlow<List<Memo>> =
            repository
                .getDeletedMemosList()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        init {
            // Keep deletion flags only for rows that still exist in current trash list.
            // This avoids alpha "bounce back" when DB removal arrives slightly later.
            trashMemos
                .onEach { memos ->
                    val existingIds = memos.asSequence().map { it.id }.toSet()
                    deletingMemoIds.update { current -> current.intersect(existingIds) }
                }.launchIn(viewModelScope)
        }

        val appPreferences: StateFlow<AppPreferencesState> =
            appConfigRepository.appPreferencesState(viewModelScope)

        val rootDirectory: StateFlow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.ROOT)
                .map { it?.raw }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val trashUiMemos: StateFlow<List<com.lomo.app.feature.main.MemoUiModel>> =
            combine(
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
                    },
                deletingMemoIds,
            ) { uiModels, deletingIds ->
                uiModels.map { uiModel ->
                    uiModel.copy(isDeleting = deletingIds.contains(uiModel.memo.id))
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun restoreMemo(memo: Memo) {
            viewModelScope.launch {
                val result =
                    runDeleteAnimationWithRollback(
                        itemId = memo.id,
                        deletingIds = deletingMemoIds,
                    ) {
                        repository.restoreMemo(memo)
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
                        deletingIds = deletingMemoIds,
                    ) {
                        repository.deletePermanently(memo)
                    }
                result.exceptionOrNull()?.let { throwable ->
                    _errorMessage.value = throwable.toUserMessage("Failed to delete memo")
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
