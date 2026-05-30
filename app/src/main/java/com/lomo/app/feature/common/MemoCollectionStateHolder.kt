package com.lomo.app.feature.common

import android.net.Uri
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.main.mapToUiModelState
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class MemoCollectionActionStateHolder(
    capabilities: MemoCollectionCapabilities,
    scope: CoroutineScope,
    trashSnapshot: (() -> List<Memo>)? = null,
    onMemoContentReplaced: ((Memo, String) -> Unit)? = null,
) {
    private val _deletingMemoIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingMemoIds: StateFlow<Set<String>> = _deletingMemoIds

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage
    val errors: MemoCollectionErrors = MemoCollectionErrors(_errorMessage)

    val uiState: StateFlow<MemoCollectionUiState> =
        combine(deletingMemoIds, errorMessage) { deletingIds, error ->
            MemoCollectionUiState(
                deletingMemoIds = deletingIds,
                errorMessage = error,
            )
        }.stateIn(scope, appWhileSubscribed(), MemoCollectionUiState())

    val actions: MemoCollectionActions =
        MemoCollectionActions(
            trashSnapshot = trashSnapshot,
            deletingMemoIds = _deletingMemoIds,
            errors = errors,
            capabilities = capabilities,
            scope = scope,
            onMemoContentReplaced = onMemoContentReplaced,
        )
}

class MemoCollectionStateHolder(
    source: Flow<List<Memo>>,
    configStateProvider: AppConfigStateProvider,
    imageMapProvider: ImageMapProvider,
    memoUiMapper: MemoUiMapper,
    capabilities: MemoCollectionCapabilities,
    scope: CoroutineScope,
) {
    val rootDirectory: StateFlow<String?> = configStateProvider.rootDirectory
    val imageDirectory: StateFlow<String?> = configStateProvider.imageDirectory
    val imageMap: StateFlow<Map<String, Uri>> = imageMapProvider.imageMap
    val appPreferences: StateFlow<AppPreferencesState> = configStateProvider.appPreferences
    private val visibleContentReplacements = MutableStateFlow<Map<String, MemoVisibleContentReplacement>>(emptyMap())
    val memos: StateFlow<List<Memo>> =
        combine(
            source,
            visibleContentReplacements,
        ) { sourceMemos, replacements ->
            sourceMemos.map { memo ->
                replacements[memo.id]?.applyTo(memo) ?: memo
            }
        }.stateIn(scope, appWhileSubscribed(), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiMemos: StateFlow<List<MemoUiModel>> =
        memos.mapToUiModelState(
            rootDirectory = rootDirectory,
            imageDirectory = imageDirectory,
            imageMap = imageMap,
            memoUiMapper = memoUiMapper,
            scope = scope,
        )

    private val actionStateHolder =
        MemoCollectionActionStateHolder(
            capabilities = capabilities,
            scope = scope,
            trashSnapshot = { memos.value },
            onMemoContentReplaced = ::replaceVisibleMemoContent,
        )

    val deletingMemoIds: StateFlow<Set<String>> = actionStateHolder.deletingMemoIds
    val errorMessage: StateFlow<String?> = actionStateHolder.errorMessage

    val uiState: StateFlow<MemoCollectionUiState> =
        combine(memos, uiMemos, deletingMemoIds, errorMessage) { memoItems, uiMemoItems, deletingIds, error ->
            MemoCollectionUiState(
                memos = memoItems,
                uiMemos = uiMemoItems,
                deletingMemoIds = deletingIds,
                errorMessage = error,
            )
        }.stateIn(scope, appWhileSubscribed(), MemoCollectionUiState())

    val actions: MemoCollectionActions = actionStateHolder.actions
    val errors: MemoCollectionErrors = actionStateHolder.errors

    private fun replaceVisibleMemoContent(
        memo: Memo,
        newContent: String,
    ) {
        visibleContentReplacements.update { replacements ->
            replacements +
                (memo.id to
                    MemoVisibleContentReplacement(
                        previousContent = memo.content,
                        replacementContent = newContent,
                    ))
        }
    }
}
