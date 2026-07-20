package com.lomo.data.repository

import com.lomo.data.engine.WorkspaceMarkdownOwner
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.source.MemoDirectoryType
import com.lomo.domain.model.Memo

sealed interface MemoProjectionChangeSet {
    val metadata: LocalFileStateEntity
    val dateKey: String

    data class Active(
        val memos: List<MemoEntity>,
        override val metadata: LocalFileStateEntity,
        override val dateKey: String,
    ) : MemoProjectionChangeSet

    data class Trash(
        val memos: List<TrashMemoEntity>,
        override val metadata: LocalFileStateEntity,
        override val dateKey: String,
    ) : MemoProjectionChangeSet
}

sealed interface MemoWorkspaceBlockRemoval {
    data object Removed : MemoWorkspaceBlockRemoval
    data class MissingSourceSpan(
        val directory: MemoDirectoryType,
        val filename: String,
        val memoId: String,
    ) : MemoWorkspaceBlockRemoval
}

sealed interface MemoWorkspaceBlockMutationResult {
    data object Applied : MemoWorkspaceBlockMutationResult
    data class MissingSourceSpan(
        val directory: MemoDirectoryType,
        val filename: String,
        val memoId: String,
    ) : MemoWorkspaceBlockMutationResult
}

sealed interface MemoWorkspaceBlockUpsertIntent {
    data object CreateNewMemo : MemoWorkspaceBlockUpsertIntent
    data object ReplaceExistingMemo : MemoWorkspaceBlockUpsertIntent
}

internal class MemoWorkspaceStore(
    private val writer: MemoWorkspaceShardWriter,
    private val workspaceOwner: WorkspaceMarkdownOwner,
) {
    suspend fun updateActiveMemoBlock(
        memo: Memo,
        newContent: String,
    ): MemoWorkspaceBlockMutationResult =
        mutationResult(
            applied = workspaceOwner.replaceMemo(null, memo.filename(), memo.id, newContent),
            directory = MemoDirectoryType.MAIN,
            filename = memo.filename(),
            memo = memo,
        )

    suspend fun appendActiveMemoBlock(
        filename: String,
        rawContent: String,
    ) {
        requireSafeMemoMarkdownFilename(filename)
        writer.appendActiveBlockContent(filename = filename, blockContent = "\n$rawContent")
    }

    suspend fun moveActiveMemoBlockToTrash(memo: Memo): MemoWorkspaceBlockMutationResult {
        val filename = memo.filename()
        val removed = workspaceOwner.removeMemo(null, filename, memo.id)
        if (!removed) return missingSpan(MemoDirectoryType.MAIN, filename, memo)
        writer.appendTrashBlock(filename = filename, blockContent = memo.rawContent)
        return MemoWorkspaceBlockMutationResult.Applied
    }

    suspend fun ensureTrashMemoBlock(memo: Memo): MemoWorkspaceBlockMutationResult {
        val filename = memo.filename()
        val exists =
            workspaceOwner.scanWorkspace(TRASH_ROOT_PATH).any { snapshot ->
                snapshot.identity == memo.id && snapshot.path.substringAfterLast('/') == filename
            }
        if (!exists) writer.appendTrashBlock(filename = filename, blockContent = memo.rawContent)
        return MemoWorkspaceBlockMutationResult.Applied
    }

    suspend fun restoreTrashMemoBlockToActive(memo: Memo): MemoWorkspaceBlockMutationResult {
        val filename = memo.filename()
        val removed = workspaceOwner.removeMemo(TRASH_ROOT_PATH, filename, memo.id)
        if (!removed) return missingSpan(MemoDirectoryType.TRASH, filename, memo)
        writer.appendActiveBlockContent(filename = filename, blockContent = memo.rawContent)
        return MemoWorkspaceBlockMutationResult.Applied
    }

    suspend fun removeTrashMemoBlock(memo: Memo): MemoWorkspaceBlockRemoval =
        if (workspaceOwner.removeMemo(TRASH_ROOT_PATH, memo.filename(), memo.id)) {
            MemoWorkspaceBlockRemoval.Removed
        } else {
            missingRemovalSpan(MemoDirectoryType.TRASH, memo.filename(), memo)
        }

    suspend fun deleteTrashShard(dateKey: String) {
        requireSafeMemoDateKey(dateKey)
        writer.deleteShard(directory = MemoDirectoryType.TRASH, filename = "$dateKey.md")
    }

    suspend fun upsertMemoBlock(
        directory: MemoDirectoryType,
        filename: String,
        currentMemo: Memo,
        replacementMemo: Memo,
        intent: MemoWorkspaceBlockUpsertIntent,
    ): MemoWorkspaceBlockMutationResult {
        requireSafeMemoMarkdownFilename(filename)
        return when (intent) {
            MemoWorkspaceBlockUpsertIntent.CreateNewMemo -> {
                writer.appendBlock(directory, filename, replacementMemo.rawContent)
                MemoWorkspaceBlockMutationResult.Applied
            }
            MemoWorkspaceBlockUpsertIntent.ReplaceExistingMemo ->
                mutationResult(
                    applied =
                        workspaceOwner.replaceMemo(
                            directory.scanRootPath(),
                            filename,
                            currentMemo.id,
                            replacementMemo.content,
                        ),
                    directory = directory,
                    filename = filename,
                    memo = currentMemo,
                )
        }
    }

    suspend fun requireMemoBlockSourceSpan(
        directory: MemoDirectoryType,
        filename: String,
        memo: Memo,
    ): MemoWorkspaceBlockMutationResult {
        val exists =
            workspaceOwner.scanWorkspace(directory.scanRootPath()).any { snapshot ->
                snapshot.identity == memo.id && snapshot.path.substringAfterLast('/') == filename
            }
        return mutationResult(exists, directory, filename, memo)
    }

    suspend fun removeMemoBlock(
        directory: MemoDirectoryType,
        filename: String,
        memo: Memo,
    ): MemoWorkspaceBlockRemoval =
        if (workspaceOwner.removeMemo(directory.scanRootPath(), filename, memo.id)) {
            MemoWorkspaceBlockRemoval.Removed
        } else {
            missingRemovalSpan(directory, filename, memo)
        }
}

private suspend fun MemoWorkspaceShardWriter.appendBlock(
    directory: MemoDirectoryType,
    filename: String,
    rawContent: String,
) {
    when (directory) {
        MemoDirectoryType.MAIN -> appendActiveBlockContent(filename, rawContent)
        MemoDirectoryType.TRASH -> appendTrashBlock(filename, rawContent)
    }
}

private fun MemoDirectoryType.scanRootPath(): String? =
    when (this) {
        MemoDirectoryType.MAIN -> null
        MemoDirectoryType.TRASH -> TRASH_ROOT_PATH
    }

private fun mutationResult(
    applied: Boolean,
    directory: MemoDirectoryType,
    filename: String,
    memo: Memo,
): MemoWorkspaceBlockMutationResult =
    if (applied) MemoWorkspaceBlockMutationResult.Applied else missingSpan(directory, filename, memo)

private fun missingSpan(
    directory: MemoDirectoryType,
    filename: String,
    memo: Memo,
): MemoWorkspaceBlockMutationResult.MissingSourceSpan =
    MemoWorkspaceBlockMutationResult.MissingSourceSpan(directory, filename, memo.id)

private fun missingRemovalSpan(
    directory: MemoDirectoryType,
    filename: String,
    memo: Memo,
): MemoWorkspaceBlockRemoval.MissingSourceSpan =
    MemoWorkspaceBlockRemoval.MissingSourceSpan(directory, filename, memo.id)

private fun Memo.filename(): String = "$dateKey.md"

private const val TRASH_ROOT_PATH = ".trash"
