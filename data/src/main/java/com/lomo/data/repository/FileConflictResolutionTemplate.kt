package com.lomo.data.repository

import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet

internal data class AppliedFileConflictChoice<T>(
    val path: String,
    val value: T,
)

internal sealed interface FileConflictApplication<out T> {
    data class Applied<T>(
        val value: T,
    ) : FileConflictApplication<T>

    data object Unresolved : FileConflictApplication<Nothing>
}

internal data class FileConflictResolutionBatch<T>(
    val unresolvedFiles: List<SyncConflictFile>,
    val appliedChoices: List<AppliedFileConflictChoice<T>>,
) {
    fun unresolvedPaths(): Set<String> =
        unresolvedFiles.mapTo(linkedSetOf(), SyncConflictFile::relativePath)
}

internal suspend fun <T> applyFileConflictChoices(
    conflictSet: SyncConflictSet,
    resolution: SyncConflictResolution,
    defaultChoice: SyncConflictResolutionChoice,
    applyChoice: suspend (file: SyncConflictFile, choice: SyncConflictResolutionChoice) -> FileConflictApplication<T>,
): FileConflictResolutionBatch<T> {
    val unresolvedFiles = mutableListOf<SyncConflictFile>()
    val appliedChoices = mutableListOf<AppliedFileConflictChoice<T>>()
    conflictSet.files.forEach { file ->
        val choice = resolution.perFileChoices[file.relativePath] ?: defaultChoice
        if (choice == SyncConflictResolutionChoice.SKIP_FOR_NOW) {
            unresolvedFiles += file
            return@forEach
        }
        when (val resolutionOutcome = applyChoice(file, choice)) {
            is FileConflictApplication.Applied ->
                appliedChoices += AppliedFileConflictChoice(
                    path = file.relativePath,
                    value = resolutionOutcome.value,
                )

            FileConflictApplication.Unresolved -> unresolvedFiles += file
        }
    }
    return FileConflictResolutionBatch(
        unresolvedFiles = unresolvedFiles,
        appliedChoices = appliedChoices,
    )
}
