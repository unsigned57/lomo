package com.lomo.app.feature.tag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.memo.MemoActionDelegate
import com.lomo.app.feature.memo.MemoFlowProcessor
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.activeDayCountState
import com.lomo.app.feature.preferences.appPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagFilterViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val memoRepository: MemoRepository,
        private val directorySettings: DirectorySettingsRepository,
        private val preferencesRepository: PreferencesRepository,
        private val imageMapProvider: ImageMapProvider,
        private val memoFlowProcessor: MemoFlowProcessor,
        private val memoActionDelegate: MemoActionDelegate,
    ) : ViewModel() {
        val tagName: String = savedStateHandle.get<String>("tagName") ?: ""
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        private val rootDirectory: StateFlow<String?> =
            directorySettings
                .getRootDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        private val imageDirectory: StateFlow<String?> =
            directorySettings
                .getImageDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val appPreferences: StateFlow<AppPreferencesState> =
            preferencesRepository.appPreferencesState(viewModelScope)

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
            memoFlowProcessor
                .mapMemoFlow(
                    memos = memos,
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = imageMap,
                ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                memoActionDelegate
                    .deleteMemo(memo)
                    .onFailure { error ->
                        _errorMessage.value = error.userMessage("Failed to delete memo")
                    }
            }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                memoActionDelegate
                    .updateMemo(memo, newContent)
                    .onFailure { error ->
                        _errorMessage.value = error.userMessage("Failed to update memo")
                    }
            }
        }

        fun saveImage(
            uri: android.net.Uri,
            onResult: (String) -> Unit,
            onError: (() -> Unit)? = null,
        ) {
            viewModelScope.launch {
                memoActionDelegate
                    .saveImage(uri)
                    .onSuccess(onResult)
                    .onFailure { error ->
                        _errorMessage.value = error.userMessage("Failed to save image")
                        onError?.invoke()
                    }
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }

        private fun Throwable.userMessage(prefix: String): String =
            if (message.isNullOrBlank()) {
                prefix
            } else {
                "$prefix: ${message.orEmpty()}"
            }
    }
