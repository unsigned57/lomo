package com.lomo.app.feature.tag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.UpdateMemoUseCase
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
        private val memoRepository: MemoRepository,
        private val mediaRepository: com.lomo.domain.repository.MediaRepository,
        private val settingsRepository: SettingsRepository,
        val mapper: com.lomo.app.feature.main.MemoUiMapper,
        private val imageMapProvider: ImageMapProvider,
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoUseCase: UpdateMemoUseCase,
    ) : ViewModel() {
        val tagName: String = savedStateHandle.get<String>("tagName") ?: ""

        private val rootDirectory: StateFlow<String?> =
            memoRepository
                .getRootDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        private val imageDirectory: StateFlow<String?> =
            memoRepository
                .getImageDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        private val defaultPreferences = AppPreferencesState.defaults()

        private val appPreferences: StateFlow<AppPreferencesState> =
            settingsRepository
                .observeAppPreferences()
                .stateInViewModel(viewModelScope, defaultPreferences)

        val dateFormat: StateFlow<String> =
            appPreferences
                .map { it.dateFormat }
                .stateInViewModel(viewModelScope, defaultPreferences.dateFormat)

        val timeFormat: StateFlow<String> =
            appPreferences
                .map { it.timeFormat }
                .stateInViewModel(viewModelScope, defaultPreferences.timeFormat)

        val shareCardStyle: StateFlow<String> =
            appPreferences
                .map { it.shareCardStyle }
                .stateInViewModel(viewModelScope, defaultPreferences.shareCardStyle)

        val shareCardShowTime: StateFlow<Boolean> =
            appPreferences
                .map { it.shareCardShowTime }
                .stateInViewModel(viewModelScope, defaultPreferences.shareCardShowTime)

        val shareCardShowBrand: StateFlow<Boolean> =
            appPreferences
                .map { it.shareCardShowBrand }
                .stateInViewModel(viewModelScope, defaultPreferences.shareCardShowBrand)

        val doubleTapEditEnabled: StateFlow<Boolean> =
            appPreferences
                .map { it.doubleTapEditEnabled }
                .stateInViewModel(viewModelScope, defaultPreferences.doubleTapEditEnabled)

        val activeDayCount: StateFlow<Int> =
            memoRepository
                .getActiveDayCount()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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
                TagMappingBundle(
                    memos = currentMemos,
                    rootDirectory = rootDirectory,
                    imageDirectory = imageDirectory,
                    imageMap = currentImageMap,
                )
            }.mapLatest { bundle ->
                mapper.mapToUiModels(
                    memos = bundle.memos,
                    rootPath = bundle.rootDirectory,
                    imagePath = bundle.imageDirectory,
                    imageMap = bundle.imageMap,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private data class TagMappingBundle(
            val memos: List<Memo>,
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, android.net.Uri>,
        )

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                try {
                    deleteMemoUseCase(memo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("TagFilterViewModel", "Failed to delete memo", e)
                }
            }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                try {
                    updateMemoUseCase(memo, newContent)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("TagFilterViewModel", "Failed to update memo", e)
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
                    val path = mediaRepository.saveImage(uri)
                    mediaRepository.syncImageCache()
                    onResult(path)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("TagFilterViewModel", "Failed to save image", e)
                    onError?.invoke()
                }
            }
        }
    }
