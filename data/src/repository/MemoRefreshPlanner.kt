package com.lomo.data.repository

import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.source.FileMetadataWithId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

internal data class MemoRefreshPlan(
    val mainFilesToUpdate: List<FileMetadataWithId>,
    val trashFilesToUpdate: List<FileMetadataWithId>,
    val visibleStateResets: List<LocalFileStateEntity>,
    val filesToDeleteInDb: Set<Pair<String, Boolean>>,
)

object MemoRefreshPlanner {
    internal suspend fun build(
        syncMetadataMap: Map<Pair<String, Boolean>, LocalFileStateEntity>,
        mainFilesMetadata: Flow<FileMetadataWithId>,
        trashFilesMetadata: Flow<FileMetadataWithId>,
        refreshStartedAt: Long,
    ): MemoRefreshPlan {
        val mainFilesToUpdate = mutableListOf<FileMetadataWithId>()
        val trashFilesToUpdate = mutableListOf<FileMetadataWithId>()
        val visibleStateResets = mutableListOf<LocalFileStateEntity>()
        val currentStateKeys = mutableSetOf<Pair<String, Boolean>>()

        mainFilesMetadata.collectRefreshMetadata(
            isTrash = false,
            syncMetadataMap = syncMetadataMap,
            refreshStartedAt = refreshStartedAt,
            currentStateKeys = currentStateKeys,
            filesToUpdate = mainFilesToUpdate,
            visibleStateResets = visibleStateResets,
        )
        trashFilesMetadata.collectRefreshMetadata(
            isTrash = true,
            syncMetadataMap = syncMetadataMap,
            refreshStartedAt = refreshStartedAt,
            currentStateKeys = currentStateKeys,
            filesToUpdate = trashFilesToUpdate,
            visibleStateResets = visibleStateResets,
        )

        val filesToDeleteInDb =
            syncMetadataMap.keys.filterTo(mutableSetOf()) { key ->
                key !in currentStateKeys
            }

        return MemoRefreshPlan(
            mainFilesToUpdate = mainFilesToUpdate,
            trashFilesToUpdate = trashFilesToUpdate,
            visibleStateResets = visibleStateResets,
            filesToDeleteInDb = filesToDeleteInDb,
        )
    }

    private suspend fun Flow<FileMetadataWithId>.collectRefreshMetadata(
        isTrash: Boolean,
        syncMetadataMap: Map<Pair<String, Boolean>, LocalFileStateEntity>,
        refreshStartedAt: Long,
        currentStateKeys: MutableSet<Pair<String, Boolean>>,
        filesToUpdate: MutableList<FileMetadataWithId>,
        visibleStateResets: MutableList<LocalFileStateEntity>,
    ) {
        collect { metadata ->
            val key = metadata.filename to isTrash
            currentStateKeys += key
            val existing = syncMetadataMap[key]
            if (existing == null || existing.lastKnownModifiedTime != metadata.lastModified) {
                filesToUpdate += metadata
            }
            if (existing != null) {
                visibleStateResets +=
                    existing.copy(
                        safUri = if (isTrash) existing.safUri else metadata.uriString ?: existing.safUri,
                        missingSince = null,
                        missingCount = 0,
                        lastSeenAt = refreshStartedAt,
                    )
            }
        }
    }
}
