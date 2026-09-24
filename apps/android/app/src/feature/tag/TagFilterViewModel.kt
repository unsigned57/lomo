package com.lomo.app.feature.tag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
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
import com.lomo.app.feature.main.MainWorkspaceCoordinator
import com.lomo.app.feature.memo.MemoActionId
import com.lomo.app.feature.memo.MemoEditorSubmissionId
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.GetMemosByTagPageUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TagFilterScreenState {
    data object Opening : TagFilterScreenState

    data object Ready : TagFilterScreenState
}


/** Collaborators of the tag-filter screen; the route handle stays a separate view-model input. */
data class TagFilterViewModelDependencies(
    val getMemosByTagPageUseCase: GetMemosByTagPageUseCase,
    val observeActiveDayCountUseCase: ObserveActiveDayCountUseCase,
    val appConfigStateProvider: AppConfigStateProvider,
    val appConfigUiCoordinator: AppConfigUiCoordinator,
    val imageMapProvider: ImageMapProvider,
    val memoUiMapper: MemoUiMapper,
    val deleteMemoUseCase: DeleteMemoUseCase,
    val updateMemoContentUseCase: UpdateMemoContentUseCase,
    val toggleMemoCheckboxUseCase: ToggleMemoCheckboxUseCase,
    val saveImageUseCase: SaveImageUseCase,
    val workspaceCoordinator: MainWorkspaceCoordinator,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TagFilterViewModel(
    dependencies: TagFilterViewModelDependencies,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val getMemosByTagPageUseCase = dependencies.getMemosByTagPageUseCase
    private val observeActiveDayCountUseCase = dependencies.observeActiveDayCountUseCase
    private val appConfigStateProvider = dependencies.appConfigStateProvider
    private val appConfigUiCoordinator = dependencies.appConfigUiCoordinator
    private val imageMapProvider = dependencies.imageMapProvider
    private val memoUiMapper = dependencies.memoUiMapper
    private val deleteMemoUseCase = dependencies.deleteMemoUseCase
    private val updateMemoContentUseCase = dependencies.updateMemoContentUseCase
    private val toggleMemoCheckboxUseCase = dependencies.toggleMemoCheckboxUseCase
    private val saveImageUseCase = dependencies.saveImageUseCase
    private val workspaceCoordinator = dependencies.workspaceCoordinator
        private val routeArgs = TagFilterRouteArgs.from(savedStateHandle)
        val tagName: String = routeArgs.tagName

        val uiState: StateFlow<TagFilterScreenState> =
            workspaceCoordinator.mount
                .map { mount -> mount.admittedAuthority }
                .distinctUntilChanged()
                .map { authority ->
                    if (authority == null) TagFilterScreenState.Opening else TagFilterScreenState.Ready
                }.stateIn(viewModelScope, appWhileSubscribed(), TagFilterScreenState.Opening)

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
                workspaceCoordinator.mount
                    .map { it.admittedAuthority }
                    .distinctUntilChanged()
                    .filterNotNull()
                    .flatMapLatest {
                        memoPager(
                            scope = viewModelScope,
                            pagingSourceFactory = { getMemosByTagPageUseCase(tag = tagName) },
                        )
                    },
            ) { input, pagingData ->
                pagingData.map { memo ->
                    memoUiMapper.mapToCachedUiModel(
                        memo = memo,
                        rootPath = input.root,
                        imagePath = input.img,
                        imageMap = input.map,
                        reminders = memo.reminders,
                    )
                }
            }.cachedIn(viewModelScope)

        private val actionStateHolder =
            MemoCollectionActionStateHolder(
                capabilities =
                    MemoCollectionCapabilities.Editable(
                        deleteMemo = deleteMemoUseCase::invoke,
                        updateMemo = updateMemoContentUseCase::invoke,
                        toggleTodo = { memo, actionSpan ->
                            toggleMemoCheckboxUseCase(memo = memo, actionSpan = actionSpan)
                        },
                        saveImage = { source, draftId ->
                            saveImageUseCase.saveWithCacheSyncStatus(source, draftId)
                        },
                    ),
                scope = viewModelScope,
                mapToUiModel = { memo ->
                    memoUiMapper.mapToCachedUiModel(
                        memo = memo,
                        rootPath = rootDir.value,
                        imagePath = imageDir.value,
                        imageMap = imageMap.value,
                        reminders = memo.reminders,
                    )
                }
            )

        val errorMessage: StateFlow<String?> = actionStateHolder.errorMessage
        val deletingMemoIds: StateFlow<Set<String>> = actionStateHolder.deletingMemoIds
        val exitAnimationRegistry = actionStateHolder.exitAnimationRegistry
        val editorSubmissionState = actionStateHolder.editorSubmissionState
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

        suspend fun submitMemoUpdate(
            submissionId: MemoEditorSubmissionId,
            memo: Memo,
            newContent: String,
        ): Boolean = actionStateHolder.actions.submitMemoUpdate(submissionId, memo, newContent)

        fun toggleTodo(
            memo: Memo,
            actionSpan: com.lomo.domain.model.markdown.MarkdownSourceSpan,
        ) {
            actionStateHolder.actions.toggleTodo(memo, actionSpan)
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
