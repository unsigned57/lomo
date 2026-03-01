package com.lomo.app.feature.tag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.activeDayCountState
import com.lomo.app.feature.preferences.appPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagFilterViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val memoRepository: MemoRepository,
        private val appConfigRepository: AppConfigRepository,
        private val imageMapProvider: ImageMapProvider,
        private val memoUiMapper: MemoUiMapper,
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoContentUseCase: UpdateMemoContentUseCase,
        private val saveImageUseCase: SaveImageUseCase,
    ) : ViewModel() {
        val tagName: String = savedStateHandle.get<String>("tagName") ?: ""
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        private val rootDirectory: StateFlow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.ROOT)
                .map { it?.raw }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        private val imageDirectory: StateFlow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.IMAGE)
                .map { it?.raw }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val appPreferences: StateFlow<AppPreferencesState> =
            appConfigRepository.appPreferencesState(viewModelScope)

        val activeDayCount: StateFlow<Int> =
            memoRepository.activeDayCountState(viewModelScope)

        val rootDir: StateFlow<String?> = rootDirectory
        val imageDir: StateFlow<String?> = imageDirectory
        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        val memos: StateFlow<List<Memo>> =
            memoRepository
                .getMemosByTagList(tag = tagName)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val uiMemos: StateFlow<List<com.lomo.app.feature.main.MemoUiModel>> =
            combine(memos, rootDir, imageDir, imageMap) { currentMemos, rootDirectory, imageDirectory, currentImageMap ->
                UiMemoMappingInput(
                    memos = currentMemos,
                    rootDirectory = rootDirectory,
                    imageDirectory = imageDirectory,
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
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                try {
                    deleteMemoUseCase(memo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to delete memo")
                }
            }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                try {
                    updateMemoContentUseCase(memo, newContent)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to update memo")
                }
            }
        }

        fun saveImage(
            uri: android.net.Uri,
            onResult: (String) -> Unit,
            onError: (() -> Unit)? = null,
        ) {
            viewModelScope.launch {
                try {
                    onResult(saveImageUseCase(uri.toString()))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to save image")
                    onError?.invoke()
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
