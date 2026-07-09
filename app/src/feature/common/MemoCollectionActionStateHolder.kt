package com.lomo.app.feature.common

import com.lomo.domain.model.Memo
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.ui.component.common.ExitAnimationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

import kotlinx.coroutines.flow.map

class MemoCollectionActionStateHolder(
    capabilities: MemoCollectionCapabilities,
    scope: CoroutineScope,
    mapToUiModel: (Memo) -> MemoUiModel,
) {
    val exitAnimationRegistry = ExitAnimationRegistry<MemoUiModel>()

    val deletingMemoIds: StateFlow<Set<String>> =
        exitAnimationRegistry.entries
            .map { it.keys }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage
    val errors: MemoCollectionErrors = MemoCollectionErrors(_errorMessage)

    val visibleContentReplacements = MutableStateFlow<Map<String, MemoVisibleContentReplacement>>(emptyMap())

    val uiState: StateFlow<MemoCollectionUiState> =
        combine(deletingMemoIds, errorMessage) { deletingIds, error ->
            MemoCollectionUiState(
                deletingMemoIds = deletingIds,
                errorMessage = error,
            )
        }.stateIn(scope, appWhileSubscribed(), MemoCollectionUiState())

    val actions: MemoCollectionActions =
        MemoCollectionActions(
            exitAnimationRegistry = exitAnimationRegistry,
            errors = errors,
            capabilities = capabilities,
            scope = scope,
            onMemoContentReplaced = ::replaceVisibleMemoContent,
            mapToUiModel = mapToUiModel,
        )


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
