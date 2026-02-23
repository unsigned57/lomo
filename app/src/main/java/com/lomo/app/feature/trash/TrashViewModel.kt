package com.lomo.app.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val settingsRepository: SettingsRepository,
        private val imageMapProvider: ImageMapProvider,
        val mapper: com.lomo.app.feature.main.MemoUiMapper,
    ) : ViewModel() {
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        private val deletingMemoIds = MutableStateFlow<Set<String>>(emptySet())

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap
        val imageDirectory: StateFlow<String?> =
            repository.getImageDirectory().stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                null,
            )

        val trashMemos: StateFlow<List<Memo>> =
            repository
                .getDeletedMemosList()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

        val rootDirectory: StateFlow<String?> =
            repository
                .getRootDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val trashUiMemos: StateFlow<List<com.lomo.app.feature.main.MemoUiModel>> =
            combine(
                trashMemos,
                rootDirectory,
                imageDirectory,
                imageMap,
                deletingMemoIds,
            ) { memos, rootDir, imageDir, currentImageMap, deletingIds ->
                TrashMappingBundle(
                    memos = memos,
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = currentImageMap,
                    deletingIds = deletingIds,
                )
            }.mapLatest { bundle ->
                mapper
                    .mapToUiModels(
                        memos = bundle.memos,
                        rootPath = bundle.rootDirectory,
                        imagePath = bundle.imageDirectory,
                        imageMap = bundle.imageMap,
                    ).map { uiModel ->
                        uiModel.copy(isDeleting = bundle.deletingIds.contains(uiModel.memo.id))
                    }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private data class TrashMappingBundle(
            val memos: List<Memo>,
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, android.net.Uri>,
            val deletingIds: Set<String>,
        )

        fun restoreMemo(memo: Memo) {
            viewModelScope.launch {
                deletingMemoIds.value = deletingMemoIds.value + memo.id
                kotlinx.coroutines.delay(300L)
                try {
                    repository.restoreMemo(memo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to restore memo: ${e.message}"
                } finally {
                    deletingMemoIds.value = deletingMemoIds.value - memo.id
                }
            }
        }

        fun deletePermanently(memo: Memo) {
            viewModelScope.launch {
                deletingMemoIds.value = deletingMemoIds.value + memo.id
                kotlinx.coroutines.delay(300L)
                try {
                    repository.deletePermanently(memo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to delete memo: ${e.message}"
                } finally {
                    deletingMemoIds.value = deletingMemoIds.value - memo.id
                }
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }
    }
