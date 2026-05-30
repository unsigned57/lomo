package com.lomo.app.feature.tag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.common.MemoCollectionCapabilities
import com.lomo.app.feature.common.MemoCollectionStateHolder
import com.lomo.app.feature.common.MemoCollectionWindowStateHolder
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.memo.MemoActionId
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.GetMemosByTagPageUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagFilterViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        getMemosByTagPageUseCase: GetMemosByTagPageUseCase,
        observeActiveDayCountUseCase: ObserveActiveDayCountUseCase,
        appConfigStateProvider: AppConfigStateProvider,
        private val appConfigUiCoordinator: AppConfigUiCoordinator,
        imageMapProvider: ImageMapProvider,
        memoUiMapper: MemoUiMapper,
        deleteMemoUseCase: DeleteMemoUseCase,
        updateMemoContentUseCase: UpdateMemoContentUseCase,
        toggleMemoCheckboxUseCase: ToggleMemoCheckboxUseCase,
        saveImageUseCase: SaveImageUseCase,
    ) : ViewModel() {
        private val routeArgs = TagFilterRouteArgs.from(savedStateHandle)
        val tagName: String = routeArgs.tagName
        private val collectionWindowStateHolder =
            MemoCollectionWindowStateHolder(
                source = { getMemosByTagPageUseCase(tag = tagName) },
                scope = viewModelScope,
            )
        private val collectionStateHolder =
            MemoCollectionStateHolder(
                source = collectionWindowStateHolder.memos,
                configStateProvider = appConfigStateProvider,
                imageMapProvider = imageMapProvider,
                memoUiMapper = memoUiMapper,
                capabilities =
                    MemoCollectionCapabilities.Editable(
                        deleteMemo = deleteMemoUseCase::invoke,
                        updateMemo = updateMemoContentUseCase::invoke,
                        toggleTodo = { memo, lineIndex, checked ->
                            toggleMemoCheckboxUseCase(memo = memo, lineIndex = lineIndex, checked = checked)
                        },
                        saveImage = saveImageUseCase::saveWithCacheSyncStatus,
                    ),
                scope = viewModelScope,
            )

        val errorMessage: StateFlow<String?> = collectionStateHolder.errorMessage
        val deletingMemoIds: StateFlow<Set<String>> = collectionStateHolder.deletingMemoIds

        val appPreferences: StateFlow<AppPreferencesState> = collectionStateHolder.appPreferences

        val activeDayCount: StateFlow<Int> =
            observeActiveDayCountUseCase()
                .stateIn(viewModelScope, appWhileSubscribed(), 0)

        val rootDir: StateFlow<String?> = collectionStateHolder.rootDirectory
        val imageDir: StateFlow<String?> = collectionStateHolder.imageDirectory
        val imageMap: StateFlow<Map<String, android.net.Uri>> = collectionStateHolder.imageMap

        val memos: StateFlow<List<Memo>> = collectionStateHolder.memos
        val canLoadMore: StateFlow<Boolean> = collectionWindowStateHolder.canLoadMore

        val uiMemos: StateFlow<List<com.lomo.app.feature.main.MemoUiModel>> =
            collectionStateHolder.uiMemos

        fun loadMore() {
            collectionWindowStateHolder.loadNextPage()
        }

        fun deleteMemo(memo: Memo) {
            collectionStateHolder.actions.delete(memo)
        }

        fun onDeleteAnimationSettled(memoId: String) {
            collectionStateHolder.actions.onDeleteAnimationSettled(memoId)
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            collectionStateHolder.actions.updateMemo(memo, newContent)
        }

        fun toggleTodo(
            memo: Memo,
            lineIndex: Int,
            checked: Boolean,
        ) {
            collectionStateHolder.actions.toggleTodo(memo, lineIndex, checked)
        }

        fun saveImage(
            uri: android.net.Uri,
            onResult: (String) -> Unit,
            onError: (() -> Unit)? = null,
        ) {
            collectionStateHolder.actions.saveImage(uri, onResult, onError)
        }

        fun clearError() {
            collectionStateHolder.errors.clear()
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

        private data class TagFilterRouteArgs(
            val tagName: String,
        ) {
            companion object {
                private const val TAG_NAME_KEY = "tagName"
                private const val TAG_NAME_ERROR =
                    "TagFilterViewModel requires non-blank tagName route argument"

                fun from(savedStateHandle: SavedStateHandle): TagFilterRouteArgs {
                    val tagName = savedStateHandle.get<String>(TAG_NAME_KEY)
                    check(!tagName.isNullOrBlank()) { TAG_NAME_ERROR }
                    return TagFilterRouteArgs(tagName = tagName)
                }
            }
        }
    }
