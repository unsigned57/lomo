package com.lomo.app.feature.memo

import android.net.Uri
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.domain.model.Memo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject

class MemoFlowProcessor
    @Inject
    constructor(
        private val mapper: MemoUiMapper,
    ) {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun mapMemoFlow(
            memos: Flow<List<Memo>>,
            rootDirectory: Flow<String?>,
            imageDirectory: Flow<String?>,
            imageMap: Flow<Map<String, Uri>>,
        ): Flow<List<MemoUiModel>> =
            combine(memos, rootDirectory, imageDirectory, imageMap) { currentMemos, rootDir, imageDir, currentImageMap ->
                MappingContext(
                    memos = currentMemos,
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = currentImageMap,
                )
            }.mapLatest { context ->
                mapper.mapToUiModels(
                    memos = context.memos,
                    rootPath = context.rootDirectory,
                    imagePath = context.imageDirectory,
                    imageMap = context.imageMap,
                )
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun mapMemoSnapshot(
            memos: List<Memo>,
            rootDirectory: Flow<String?>,
            imageDirectory: Flow<String?>,
            imageMap: Flow<Map<String, Uri>>,
        ): Flow<List<MemoUiModel>> =
            combine(rootDirectory, imageDirectory, imageMap) { rootDir, imageDir, currentImageMap ->
                MappingContext(
                    memos = memos,
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = currentImageMap,
                )
            }.mapLatest { context ->
                mapper.mapToUiModels(
                    memos = context.memos,
                    rootPath = context.rootDirectory,
                    imagePath = context.imageDirectory,
                    imageMap = context.imageMap,
                )
            }

        private data class MappingContext(
            val memos: List<Memo>,
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, Uri>,
        )
    }
