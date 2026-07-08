package com.lomo.data.repository
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.parser.MarkdownParser
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
class MemoWorkspaceStore
constructor(
        private val reader: MemoWorkspaceReader,
        private val writer: MemoWorkspaceShardWriter,
        private val parser: MarkdownParser,
    ) {
        suspend fun updateActiveMemoBlock(
            memo: Memo,
            newContent: String,
            timestampText: String,
        ): MemoWorkspaceBlockMutationResult {
            requireSafeMemoDateKey(memo.dateKey)
            val filename = memo.filename()
            val currentFileContent =
                reader.readActiveShardContent(filename)
                    ?: return missingSpan(MemoDirectoryType.MAIN, filename, memo)
            val updatedContent =
                replaceMemoBlockContent(
                    currentFileContent = currentFileContent,
                    dateKey = memo.dateKey,
                    memo = memo,
                    replacementLines = buildUpdatedMemoLines(newContent, timestampText),
                    parser = parser,
                ) ?: return missingSpan(MemoDirectoryType.MAIN, filename, memo)
            writer.persistMainShard(filename = filename, content = updatedContent)
            return MemoWorkspaceBlockMutationResult.Applied
        }
        suspend fun appendActiveMemoBlock(
            filename: String,
            rawContent: String,
        ) {
            requireSafeMemoMarkdownFilename(filename)
            writer.appendActiveBlockContent(filename = filename, blockContent = "\n$rawContent")
        }
        suspend fun moveActiveMemoBlockToTrash(memo: Memo): MemoWorkspaceBlockMutationResult {
            requireSafeMemoDateKey(memo.dateKey)
            val filename = memo.filename()
            val currentFileContent =
                reader.readActiveShardContent(filename)
                    ?: return missingSpan(MemoDirectoryType.MAIN, filename, memo)
            val removedBlock =
                removeMemoBlockFromContent(currentFileContent, memo.dateKey, memo, parser)
                    ?: return missingSpan(MemoDirectoryType.MAIN, filename, memo)
            writer.persistRemovedActiveBlock(filename = filename, removedBlock = removedBlock)
            writer.appendTrashBlock(filename = filename, blockContent = removedBlock.blockContent)
            return MemoWorkspaceBlockMutationResult.Applied
        }
        suspend fun ensureTrashMemoBlock(memo: Memo): MemoWorkspaceBlockMutationResult {
            requireSafeMemoDateKey(memo.dateKey)
            val filename = memo.filename()
            val trashContent = reader.readTrashShardContent(filename)
            if (trashContent != null && containsMemoBlock(trashContent, memo.dateKey, memo, parser)) {
                return MemoWorkspaceBlockMutationResult.Applied
            }
            writer.appendTrashBlock(filename = filename, blockContent = memo.toBlockContent())
            return MemoWorkspaceBlockMutationResult.Applied
        }
        suspend fun restoreTrashMemoBlockToActive(memo: Memo): MemoWorkspaceBlockMutationResult {
            requireSafeMemoDateKey(memo.dateKey)
            val filename = memo.filename()
            val trashContent =
                reader.readTrashShardContent(filename)
                    ?: return missingSpan(MemoDirectoryType.TRASH, filename, memo)
            val removedBlock =
                removeMemoBlockFromContent(trashContent, memo.dateKey, memo, parser)
                    ?: return missingSpan(MemoDirectoryType.TRASH, filename, memo)
            writer.persistRemovedTrashBlock(filename = filename, removedBlock = removedBlock)
            writer.appendActiveBlockContent(filename = filename, blockContent = removedBlock.blockContent)
            return MemoWorkspaceBlockMutationResult.Applied
        }
        suspend fun removeTrashMemoBlock(memo: Memo): MemoWorkspaceBlockRemoval {
            requireSafeMemoDateKey(memo.dateKey)
            val filename = memo.filename()
            val trashContent = reader.readTrashShardContent(filename)
                ?: return missingRemovalSpan(MemoDirectoryType.TRASH, filename, memo)
            val removedBlock = removeMemoBlockFromContent(trashContent, memo.dateKey, memo, parser)
                ?: return missingRemovalSpan(MemoDirectoryType.TRASH, filename, memo)
            writer.persistRemovedTrashBlock(filename = filename, removedBlock = removedBlock)
            return MemoWorkspaceBlockRemoval.Removed
        }
        /**
         * Deletes an entire trash shard file in one operation. Used by clearTrash to batch the SAF
         * I/O: emptying the trash removes every block in a shard, so a single delete replaces a
         * per-memo read-rewrite cycle.
         */
        suspend fun deleteTrashShard(dateKey: String) {
            requireSafeMemoDateKey(dateKey)
            writer.deleteShard(directory = MemoDirectoryType.TRASH, filename = "$dateKey.md")
        }
        suspend fun upsertMemoBlock(
            directory: MemoDirectoryType,
            filename: String,
            currentMemo: Memo,
            replacementRawContent: String,
            intent: MemoWorkspaceBlockUpsertIntent,
        ): MemoWorkspaceBlockMutationResult {
            requireSafeMemoMarkdownFilename(filename)
            val currentFileContent = reader.readShardContentForMutation(directory, filename)
            val updatedContent =
                when (intent) {
                    MemoWorkspaceBlockUpsertIntent.CreateNewMemo ->
                        if (currentFileContent.isNullOrBlank()) {
                            replacementRawContent
                        } else {
                            "$currentFileContent\n$replacementRawContent"
                        }
                    MemoWorkspaceBlockUpsertIntent.ReplaceExistingMemo ->
                        if (currentFileContent.isNullOrBlank()) {
                            return missingSpan(directory, filename, currentMemo)
                        } else {
                            replaceMemoBlockContent(
                                currentFileContent = currentFileContent,
                                dateKey = filename.removeSuffix(".md"),
                                memo = currentMemo,
                                replacementLines = replacementRawContent.lines(),
                                parser = parser,
                            ) ?: return missingSpan(directory, filename, currentMemo)
                        }
                }
            writer.persistShard(directory = directory, filename = filename, content = updatedContent)
            return MemoWorkspaceBlockMutationResult.Applied
        }
        suspend fun requireMemoBlockSourceSpan(
            directory: MemoDirectoryType,
            filename: String,
            memo: Memo,
        ): MemoWorkspaceBlockMutationResult {
            requireSafeMemoMarkdownFilename(filename)
            val currentFileContent =
                reader.readShardContentForMutation(directory, filename)
                    ?: return missingSpan(directory, filename, memo)
            if (!containsMemoBlock(currentFileContent, filename.removeSuffix(".md"), memo, parser)) {
                return missingSpan(directory, filename, memo)
            }
            return MemoWorkspaceBlockMutationResult.Applied
        }
        suspend fun removeMemoBlock(
            directory: MemoDirectoryType,
            filename: String,
            memo: Memo,
        ): MemoWorkspaceBlockRemoval {
            requireSafeMemoMarkdownFilename(filename)
            val currentFileContent =
                reader.readShardContentForMutation(directory, filename)
                    ?: return missingRemovalSpan(directory, filename, memo)
            val removedBlock =
                removeMemoBlockFromContent(currentFileContent, filename.removeSuffix(".md"), memo, parser)
                    ?: return missingRemovalSpan(directory, filename, memo)
            if (removedBlock.remainingContent.trim().isEmpty()) {
                writer.deleteShard(directory = directory, filename = filename)
            } else {
                writer.persistShard(directory = directory, filename = filename, content = removedBlock.remainingContent)
            }
            return MemoWorkspaceBlockRemoval.Removed
        }
    }
// Stateless span helpers kept at file level so MemoWorkspaceStore stays within the per-class
// function budget (TooManyFunctions); they construct result values and touch no instance state.
private fun missingSpan(
    directory: MemoDirectoryType,
    filename: String,
    memo: Memo,
): MemoWorkspaceBlockMutationResult.MissingSourceSpan =
    MemoWorkspaceBlockMutationResult.MissingSourceSpan(
        directory = directory,
        filename = filename,
        memoId = memo.id,
    )
private fun missingRemovalSpan(
    directory: MemoDirectoryType,
    filename: String,
    memo: Memo,
): MemoWorkspaceBlockRemoval.MissingSourceSpan =
    MemoWorkspaceBlockRemoval.MissingSourceSpan(
        directory = directory,
        filename = filename,
        memoId = memo.id,
    )
