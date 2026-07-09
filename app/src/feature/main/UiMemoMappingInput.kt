package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.domain.model.Memo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

internal data class UiMemoMappingInput(
    val memos: List<Memo>,
    val rootDirectory: String?,
    val imageDirectory: String?,
    val imageMap: Map<String, Uri>,
    val imageDependencySignature: String,
    val prioritizedMemoIds: Set<String>,
)

internal fun UiMemoMappingInput.hasSameUiDependencies(other: UiMemoMappingInput): Boolean =
    memos == other.memos &&
        rootDirectory == other.rootDirectory &&
        imageDirectory == other.imageDirectory &&
        imageDependencySignature == other.imageDependencySignature &&
        prioritizedMemoIds == other.prioritizedMemoIds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun Flow<List<Memo>>.mapToUiModels(
    rootDirectory: Flow<String?>,
    imageDirectory: Flow<String?>,
    imageMap: Flow<Map<String, Uri>>,
    memoUiMapper: MemoUiMapper,
    transformMemos: (List<Memo>) -> List<Memo> = { it },
    dependencyMemos: (List<Memo>) -> List<Memo> = { it },
    prioritizedMemoIds: (List<Memo>) -> Set<String> = { emptySet() },
): Flow<List<MemoUiModel>> =
    combine(this, rootDirectory, imageDirectory, imageMap) { memos, rootDir, imageDir, currentImageMap ->
        val mappedMemos = transformMemos(memos)
        UiMemoMappingInput(
            memos = mappedMemos,
            rootDirectory = rootDir,
            imageDirectory = imageDir,
            imageMap = currentImageMap,
            imageDependencySignature = buildMemoListImageDependencySignature(dependencyMemos(memos), currentImageMap),
            prioritizedMemoIds = prioritizedMemoIds(mappedMemos),
        )
    }.distinctUntilChanged(UiMemoMappingInput::hasSameUiDependencies)
        .mapLatest { input ->
            memoUiMapper.mapToUiModels(
                memos = input.memos,
                rootPath = input.rootDirectory,
                imagePath = input.imageDirectory,
                imageMap = input.imageMap,
                prioritizedMemoIds = input.prioritizedMemoIds,
            )
        }

internal fun Flow<List<Memo>>.mapToUiModelState(
    rootDirectory: Flow<String?>,
    imageDirectory: Flow<String?>,
    imageMap: Flow<Map<String, Uri>>,
    memoUiMapper: MemoUiMapper,
    scope: CoroutineScope,
    transformMemos: (List<Memo>) -> List<Memo> = { it },
    dependencyMemos: (List<Memo>) -> List<Memo> = { it },
    prioritizedMemoIds: (List<Memo>) -> Set<String> = { emptySet() },
): StateFlow<List<MemoUiModel>> =
    mapToUiModels(
        rootDirectory = rootDirectory,
        imageDirectory = imageDirectory,
        imageMap = imageMap,
        memoUiMapper = memoUiMapper,
        transformMemos = transformMemos,
        dependencyMemos = dependencyMemos,
        prioritizedMemoIds = prioritizedMemoIds,
    ).stateIn(scope, appWhileSubscribed(), emptyList())
