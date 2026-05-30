package com.lomo.data.repository

import com.lomo.data.source.MemoDirectoryType
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionLifecycleState

internal suspend fun flushVersionRestoreFromOutbox(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
): Boolean {
    require(command.operation == MemoLifecycleOperation.VERSION_RESTORE) {
        "VERSION_RESTORE outbox completion requires version-restore lifecycle command: " +
            command.metadata.operationId.value
    }
    val target = requireNotNull(command.revisionRestoreTarget) {
        "VERSION_RESTORE outbox completion requires target revision: ${command.metadata.operationId.value}"
    }
    val restoredAssets = runtime.memoVersionRestoreSupport.readRevisionRestoreAssets(target.revisionId)
    restoredAssets.forEach { asset ->
        runtime.workspaceMediaAccess.writeFileFromStream(
            category = asset.category,
            filename = asset.filename,
            source = asset.writeTo,
        )
    }
    restoreRevisionWorkspace(runtime, command)
    runtime.mediaRepository.refreshImageLocations()
    runtime.memoVersionRestoreSupport.recordRevisionRestoreHandoff(command)
    recordRevisionRestoreRemoteState(runtime, command)
    return true
}

private suspend fun restoreRevisionWorkspace(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
) {
    val target = requireNotNull(command.revisionRestoreTarget)
    when (target.lifecycleState) {
        MemoRevisionLifecycleState.ACTIVE -> {
            val sourceDirectory = command.sourceMemo.sourceDirectory()
            requireVersionRestoreSourceSpan(runtime, command, sourceDirectory)
            requireVersionRestoreUpsertApplied(
                result =
                    runtime.workspaceStore.upsertMemoBlock(
                        directory = MemoDirectoryType.MAIN,
                        filename = command.filename,
                        currentMemo = command.sourceMemo.copy(isDeleted = false),
                        replacementRawContent = target.rawContent,
                        intent =
                            if (command.targetAlreadyReplacedSource(sourceDirectory)) {
                                MemoWorkspaceBlockUpsertIntent.ReplaceExistingMemo
                            } else {
                                MemoWorkspaceBlockUpsertIntent.CreateNewMemo
                            },
                    ),
                operationId = command.metadata.operationId.value,
            )
            removeRequiredSourceMemoBlock(
                runtime = runtime,
                command = command,
            )
        }
        MemoRevisionLifecycleState.TRASHED -> {
            val sourceDirectory = command.sourceMemo.sourceDirectory()
            requireVersionRestoreSourceSpan(runtime, command, sourceDirectory)
            requireVersionRestoreUpsertApplied(
                result =
                    runtime.workspaceStore.upsertMemoBlock(
                        directory = MemoDirectoryType.TRASH,
                        filename = command.filename,
                        currentMemo = command.sourceMemo.copy(isDeleted = true),
                        replacementRawContent = target.rawContent,
                        intent =
                            if (command.targetAlreadyReplacedSource(sourceDirectory)) {
                                MemoWorkspaceBlockUpsertIntent.ReplaceExistingMemo
                            } else {
                                MemoWorkspaceBlockUpsertIntent.CreateNewMemo
                            },
                    ),
                operationId = command.metadata.operationId.value,
            )
            removeRequiredSourceMemoBlock(
                runtime = runtime,
                command = command,
            )
        }
        MemoRevisionLifecycleState.DELETED ->
            removeRequiredSourceMemoBlock(
                runtime = runtime,
                command = command,
            )
    }
}

private suspend fun requireVersionRestoreSourceSpan(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
    sourceDirectory: MemoDirectoryType,
) {
    requireVersionRestoreUpsertApplied(
        result =
            runtime.workspaceStore.requireMemoBlockSourceSpan(
                directory = sourceDirectory,
                filename = command.sourceFilename,
                memo = command.sourceMemo.copy(isDeleted = sourceDirectory == MemoDirectoryType.TRASH),
            ),
        operationId = command.metadata.operationId.value,
    )
}

private suspend fun removeRequiredSourceMemoBlock(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
) {
    val sourceDirectory = command.sourceMemo.sourceDirectory()
    if (command.targetAlreadyReplacedSource(sourceDirectory)) {
        return
    }
    val result =
        runtime.workspaceStore.removeMemoBlock(
            directory = sourceDirectory,
            filename = command.sourceFilename,
            memo = command.sourceMemo.copy(isDeleted = sourceDirectory == MemoDirectoryType.TRASH),
        )
    requireVersionRestoreRemovalApplied(
        result = result,
        operationId = command.metadata.operationId.value,
    )
}

private fun Memo.sourceDirectory(): MemoDirectoryType =
    if (isDeleted) {
        MemoDirectoryType.TRASH
    } else {
        MemoDirectoryType.MAIN
    }

private fun MemoLifecycleCommand.targetAlreadyReplacedSource(sourceDirectory: MemoDirectoryType): Boolean {
    val target = requireNotNull(revisionRestoreTarget)
    val targetDirectory =
        when (target.lifecycleState) {
            MemoRevisionLifecycleState.ACTIVE -> MemoDirectoryType.MAIN
            MemoRevisionLifecycleState.TRASHED -> MemoDirectoryType.TRASH
            MemoRevisionLifecycleState.DELETED -> return false
        }
    return targetDirectory == sourceDirectory && filename == sourceFilename
}

private fun requireVersionRestoreUpsertApplied(
    result: MemoWorkspaceBlockMutationResult,
    operationId: String,
) {
    when (result) {
        MemoWorkspaceBlockMutationResult.Applied -> Unit
        is MemoWorkspaceBlockMutationResult.MissingSourceSpan ->
            throw MemoOutboxLifecycleCompletionException(
                "VERSION_RESTORE missing source span for memo ${result.memoId} in ${result.directory} shard " +
                    "${result.filename}: $operationId",
            )
    }
}

private fun requireVersionRestoreRemovalApplied(
    result: MemoWorkspaceBlockRemoval,
    operationId: String,
) {
    when (result) {
        MemoWorkspaceBlockRemoval.Removed -> Unit
        is MemoWorkspaceBlockRemoval.MissingSourceSpan ->
            throw MemoOutboxLifecycleCompletionException(
                "VERSION_RESTORE missing source span for memo ${result.memoId} in ${result.directory} shard " +
                    "${result.filename}: $operationId",
            )
    }
}

private suspend fun recordRevisionRestoreRemoteState(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
) {
    val target = requireNotNull(command.revisionRestoreTarget)
    when (target.lifecycleState) {
        MemoRevisionLifecycleState.ACTIVE -> recordActiveRevisionRestoreRemoteState(runtime, command)
        MemoRevisionLifecycleState.TRASHED,
        MemoRevisionLifecycleState.DELETED,
        -> recordRemovedRevisionRestoreRemoteState(runtime, command)
    }
}

private suspend fun recordActiveRevisionRestoreRemoteState(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
) {
    runtime.s3LocalChangeRecorder.recordMemoUpsert(command.filename)
    runtime.webDavLocalChangeRecorder.recordMemoUpsert(command.filename)
    if (command.sourceFilename != command.filename) {
        runtime.s3LocalChangeRecorder.recordMemoDelete(command.sourceFilename)
        runtime.webDavLocalChangeRecorder.recordMemoDelete(command.sourceFilename)
    }
}

private suspend fun recordRemovedRevisionRestoreRemoteState(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
) {
    runtime.s3LocalChangeRecorder.recordMemoDelete(command.sourceFilename)
    runtime.webDavLocalChangeRecorder.recordMemoDelete(command.sourceFilename)
    if (command.sourceFilename != command.filename) {
        runtime.s3LocalChangeRecorder.recordMemoDelete(command.filename)
        runtime.webDavLocalChangeRecorder.recordMemoDelete(command.filename)
    }
}
