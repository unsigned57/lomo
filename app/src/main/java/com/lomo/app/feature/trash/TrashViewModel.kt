package com.lomo.app.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.map
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.MemoCollectionActionStateHolder
import com.lomo.app.feature.common.MemoCollectionCapabilities
import com.lomo.app.feature.common.DeleteAnimationItem
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.memoPager
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.MemoTrashUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        memoTrashUseCase: MemoTrashUseCase,
        appConfigStateProvider: AppConfigStateProvider,
        imageMapProvider: ImageMapProvider,
        memoUiMapper: MemoUiMapper,
    ) : ViewModel() {

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
                    pagingSourceFactory = memoTrashUseCase::getDeletedMemosPagingSource,
                ),
            ) { input, pagingData ->
                pagingData.map { memo ->
                    memoUiMapper.mapToUiModel(memo, input.root, input.img, input.map)
                }
            }

        private val actionStateHolder =
            MemoCollectionActionStateHolder(
                capabilities =
                    MemoCollectionCapabilities.Trash(
                        restoreMemo = memoTrashUseCase::restoreMemo,
                        deletePermanently = memoTrashUseCase::deletePermanently,
                        clearTrash = memoTrashUseCase::clearTrash,
                    ),
                scope = viewModelScope,
                mapToUiModel = { memo ->
                    val input = mappingInput.value
                    memoUiMapper.mapToUiModel(memo, input.root, input.img, input.map)
                }
            )

        val appPreferences = appConfigStateProvider.appPreferences
        val errorMessage: StateFlow<String?> = actionStateHolder.errorMessage
        val deletingMemoIds: StateFlow<Set<String>> = actionStateHolder.deletingMemoIds
        val exitAnimationRegistry = actionStateHolder.exitAnimationRegistry

        fun restoreMemo(
            memo: Memo,
            anchoredAfterKey: String?,
        ) = actionStateHolder.actions.restore(memo, anchoredAfterKey)

        fun deletePermanently(
            memo: Memo,
            anchoredAfterKey: String?,
        ) = actionStateHolder.actions.deletePermanently(memo, anchoredAfterKey)

        fun clearTrash(items: List<DeleteAnimationItem<Memo>>) =
            actionStateHolder.actions.clearTrash(items)

        fun onDeleteAnimationSettled(memoId: String) = exitAnimationRegistry.markExitAnimationSettled(memoId)

        fun clearError() = actionStateHolder.errors.clear()
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
