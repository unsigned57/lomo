package com.lomo.data.repository

import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.source.FileMetadataWithId
import javax.inject.Inject

internal data class MemoRefreshPlan(
    val discoveredMainStates: List<LocalFileStateEntity>,
    val mainFilesToUpdate: List<FileMetadataWithId>,
    val trashFilesToUpdate: List<FileMetadataWithId>,
    val filesToDeleteInDb: Set<Pair<String, Boolean>>,
)

class MemoRefreshPlanner
    @Inject
    constructor() {
    internal fun build(
        syncMetadataMap: Map<Pair<String, Boolean>, LocalFileStateEntity>,
        mainFilesMetadata: List<FileMetadataWithId>,
        trashFilesMetadata: List<FileMetadataWithId>,
    ): MemoRefreshPlan {
        val discoveredMainStates =
            mainFilesMetadata.mapNotNull { meta ->
                val key = meta.filename to false
                val existing = syncMetadataMap[key]
                val safUri = meta.uriString ?: existing?.safUri
                if (safUri == null && existing == null) {
                    null
                } else {
                    LocalFileStateEntity(
                        filename = meta.filename,
                        isTrash = false,
                        safUri = safUri,
                        lastKnownModifiedTime = existing?.lastKnownModifiedTime ?: 0L,
                    )
                }
            }

        val mainFilesToUpdate =
            mainFilesMetadata.filter { meta ->
                val existing = syncMetadataMap[meta.filename to false]
                existing == null || existing.lastKnownModifiedTime != meta.lastModified
            }

        val trashFilesToUpdate =
            trashFilesMetadata.filter { meta ->
                val existing = syncMetadataMap[meta.filename to true]
                existing == null || existing.lastKnownModifiedTime != meta.lastModified
            }

        val currentMainStateKeys = mainFilesMetadata.map { it.filename to false }.toSet()
        val currentTrashStateKeys = trashFilesMetadata.map { it.filename to true }.toSet()
        val filesToDeleteInDb =
            syncMetadataMap.keys.filterTo(mutableSetOf()) { key ->
                if (key.second) {
                    key !in currentTrashStateKeys
                } else {
                    key !in currentMainStateKeys
                }
            }

        return MemoRefreshPlan(
            discoveredMainStates = discoveredMainStates,
            mainFilesToUpdate = mainFilesToUpdate,
            trashFilesToUpdate = trashFilesToUpdate,
            filesToDeleteInDb = filesToDeleteInDb,
        )
    }
}
