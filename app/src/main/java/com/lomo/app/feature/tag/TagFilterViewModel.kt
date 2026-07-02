package com.lomo.app.feature.tag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.map
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.common.MemoCollectionActionStateHolder
import com.lomo.app.feature.common.MemoCollectionCapabilities
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.memoPager
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.main.MemoUiModel
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

        val activeDayCount: StateFlow<Int> =
            observeActiveDayCountUseCase()
                .stateIn(viewModelScope, appWhileSubscribed(), 0)

        private val mappingInput =
            combine(
                appConfigStateProvider.rootDirectory,
                appConfigStateProvider.imageDirectory,
                imageMapProvider.imageMap,
            ) { root, img, map -> UiMappingInput(root, img, map) }
                .distinctUntilChanged { old, new -> old.sameForPaging(new) }
                .stateIn(viewModelScope, appWhileSubscribed(), UiMappingInput.EMPTY)

        val pagedUiMemos: Flow<PagingData<MemoUiModel>> =
            combine(
                mappingInput,
                memoPager(
                    scope = viewModelScope,
                    pagingSourceFactory = { getMemosByTagPageUseCase(tag = tagName) },
                ),
            ) { input, pagingData ->
                pagingData.map { memo ->
                    memoUiMapper.mapToUiModel(memo, input.root, input.img, input.map)
                }
            }

        private val actionStateHolder =
            MemoCollectionActionStateHolder(
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
                mapToUiModel = { memo ->
                    memoUiMapper.mapToUiModel(memo, rootDir.value, imageDir.value, imageMap.value)
                }
            )

        val errorMessage: StateFlow<String?> = actionStateHolder.errorMessage
        val deletingMemoIds: StateFlow<Set<String>> = actionStateHolder.deletingMemoIds
        val exitAnimationRegistry = actionStateHolder.exitAnimationRegistry
        val appPreferences: StateFlow<AppPreferencesState> = appConfigStateProvider.appPreferences
        val rootDir: StateFlow<String?> = appConfigStateProvider.rootDirectory
        val imageDir: StateFlow<String?> = appConfigStateProvider.imageDirectory
        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        fun deleteMemo(
            memo: Memo,
            anchoredAfterKey: String?,
        ) {
            actionStateHolder.actions.delete(memo, anchoredAfterKey)
        }

        fun onDeleteAnimationSettled(memoId: String) {
            exitAnimationRegistry.markExitAnimationSettled(memoId)
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            actionStateHolder.actions.updateMemo(memo, newContent)
        }

        fun toggleTodo(
            memo: Memo,
            lineIndex: Int,
            checked: Boolean,
        ) {
            actionStateHolder.actions.toggleTodo(memo, lineIndex, checked)
        }

        fun saveImage(
            uri: android.net.Uri,
            onResult: (String) -> Unit,
            onError: (() -> Unit)? = null,
        ) {
            actionStateHolder.actions.saveImage(uri, onResult, onError)
        }

        fun clearError() {
            actionStateHolder.errors.clear()
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

private data class UiMappingInput(
    val root: String?,
    val img: String?,
    val map: Map<String, android.net.Uri>,
) {
    fun sameForPaging(other: UiMappingInput): Boolean {
        return root == other.root && img == other.img && map == other.map
    }

    companion object {
        val EMPTY = UiMappingInput(null, null, emptyMap())
    }
}
