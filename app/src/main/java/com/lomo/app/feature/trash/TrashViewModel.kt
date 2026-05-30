package com.lomo.app.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.MemoCollectionCapabilities
import com.lomo.app.feature.common.MemoCollectionStateHolder
import com.lomo.app.feature.common.MemoCollectionWindowStateHolder
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.MemoTrashUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
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
        private val collectionWindowStateHolder =
            MemoCollectionWindowStateHolder(
                source = memoTrashUseCase::getDeletedMemosPagingSource,
                scope = viewModelScope,
            )
        private val collectionStateHolder =
            MemoCollectionStateHolder(
                source = collectionWindowStateHolder.memos,
                configStateProvider = appConfigStateProvider,
                imageMapProvider = imageMapProvider,
                memoUiMapper = memoUiMapper,
                capabilities =
                    MemoCollectionCapabilities.Trash(
                        restoreMemo = memoTrashUseCase::restoreMemo,
                        deletePermanently = memoTrashUseCase::deletePermanently,
                        clearTrash = memoTrashUseCase::clearTrash,
                    ),
                scope = viewModelScope,
            )

        val errorMessage: StateFlow<String?> = collectionStateHolder.errorMessage

        val deletingMemoIds: StateFlow<Set<String>> = collectionStateHolder.deletingMemoIds

        val imageMap: StateFlow<Map<String, android.net.Uri>> = collectionStateHolder.imageMap
        val imageDirectory: StateFlow<String?> = collectionStateHolder.imageDirectory

        val trashMemos: StateFlow<List<Memo>> = collectionStateHolder.memos
        val canLoadMore: StateFlow<Boolean> = collectionWindowStateHolder.canLoadMore

        val appPreferences: StateFlow<AppPreferencesState> = collectionStateHolder.appPreferences

        val rootDirectory: StateFlow<String?> = collectionStateHolder.rootDirectory

        val trashUiMemos: StateFlow<List<com.lomo.app.feature.main.MemoUiModel>> =
            collectionStateHolder.uiMemos

        fun loadMore() {
            collectionWindowStateHolder.loadNextPage()
        }

        fun restoreMemo(memo: Memo) {
            collectionStateHolder.actions.restore(memo)
        }

        fun deletePermanently(memo: Memo) {
            collectionStateHolder.actions.deletePermanently(memo)
        }

        fun clearTrash() {
            collectionStateHolder.actions.clearTrash()
        }

        fun onDeleteAnimationSettled(memoId: String) {
            collectionStateHolder.actions.onDeleteAnimationSettled(memoId)
        }

        fun clearError() {
            collectionStateHolder.errors.clear()
        }

    }
