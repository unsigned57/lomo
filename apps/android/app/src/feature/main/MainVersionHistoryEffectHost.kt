package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.feature.memo.MemoVersionHistoryUiMapper
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.withContext

@Composable
internal fun MainVersionHistoryEffectHost(bindings: MainVersionHistoryEffectBindings) {
    val versionHistoryState by bindings.versionHistoryStateFlow.collectAsStateWithLifecycle()
    val rootDirectory by bindings.rootDirectoryFlow.collectAsStateWithLifecycle()
    val imageDirectory by bindings.imageDirectoryFlow.collectAsStateWithLifecycle()
    val imageMap by bindings.imageMapFlow.collectAsStateWithLifecycle()
    val stableImageMap = remember(imageMap) { imageMap.toImmutableMap() }

    VersionHistoryOverlay(
        state = versionHistoryState,
        rootPath = rootDirectory,
        imagePath = imageDirectory,
        imageMap = stableImageMap,
        onDismiss = bindings.onDismiss,
        onLoadMore = bindings.onLoadMore,
        onRestore = bindings.onRestore,
    )
}

@Composable
internal fun VersionHistoryOverlay(
    state: MainVersionHistoryState,
    rootPath: String?,
    imagePath: String?,
    imageMap: ImmutableMap<String, android.net.Uri>,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onRestore: (Memo, MemoRevision) -> Unit,
) {
    val markdownWorkspaceRepository =
        org.koin.compose.koinInject<com.lomo.domain.repository.MarkdownWorkspaceRepository>()
    val dispatcherProvider =
        org.koin.compose.koinInject<com.lomo.domain.usecase.DispatcherProvider>()
    val mapper = remember(markdownWorkspaceRepository) { MemoVersionHistoryUiMapper(markdownWorkspaceRepository) }
    when (state) {
        is MainVersionHistoryState.Loading -> {
            com.lomo.app.feature.memo.MemoVersionHistorySheet(
                versions = persistentListOf(),
                isLoading = true,
                canLoadMore = false,
                isLoadingMore = false,
                isRestoreInProgress = false,
                restoringRevisionId = null,
                onLoadMore = {},
                onRestore = {},
                onDismiss = onDismiss,
            )
        }

        is MainVersionHistoryState.Loaded -> {
            var pendingRestoreRevisionId by remember(state.memo.id) { mutableStateOf<String?>(null) }
            var versionUiModels by
                remember {
                    mutableStateOf<ImmutableList<com.lomo.app.feature.memo.MemoVersionHistoryUiModel>>(
                        persistentListOf(),
                    )
                }
            LaunchedEffect(state.isRestoring, state.restoringRevisionId, state.memo.id) {
                if (!state.isRestoring && state.restoringRevisionId == null) {
                    pendingRestoreRevisionId = null
                }
            }
            LaunchedEffect(state.versions, rootPath, imagePath, imageMap) {
                val revisions = state.versions
                versionUiModels =
                    withContext(dispatcherProvider.default) {
                        mapper.mapToUiModels(
                            revisions = revisions,
                            rootPath = rootPath,
                            imagePath = imagePath,
                            imageMap = imageMap,
                        ).toImmutableList()
                    }
            }
            val restoringRevisionId = pendingRestoreRevisionId ?: state.restoringRevisionId
            val isRestoreInProgress = state.isRestoring || restoringRevisionId != null
            com.lomo.app.feature.memo.MemoVersionHistorySheet(
                versions = versionUiModels,
                isLoading = versionUiModels.isEmpty() && state.versions.isNotEmpty(),
                canLoadMore = state.hasMore,
                isLoadingMore = state.isLoadingMore,
                isRestoreInProgress = isRestoreInProgress,
                restoringRevisionId = restoringRevisionId,
                onLoadMore = onLoadMore,
                onRestore = { version ->
                    if (restoringRevisionId == null && !state.isRestoring) {
                        pendingRestoreRevisionId = version.revisionId
                        onRestore(state.memo, version)
                    }
                },
                onDismiss = onDismiss,
            )
        }

        MainVersionHistoryState.Hidden -> {}
    }
}
