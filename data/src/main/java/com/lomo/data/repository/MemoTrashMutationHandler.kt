package com.lomo.data.repository

import com.lomo.data.local.dao.MemoImageDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.projection.MemoProjectionProjector
import com.lomo.domain.model.Memo
import timber.log.Timber
import javax.inject.Inject

/**
 * Encapsulates memo -> trash lifecycle mutations.
 */
class MemoTrashMutationHandler
    @Inject
    constructor(
        private val workspaceStore: MemoWorkspaceStore,
        private val memoWriteDao: MemoWriteDao,
        private val memoTagDao: MemoTagDao,
        private val memoImageDao: MemoImageDao,
        private val memoTrashDao: MemoTrashDao,
        private val memoVersionRecorder: AsyncMemoVersionRecorder,
    ) {
        suspend fun moveToTrash(memo: Memo) {
            if (!moveToTrashFileOnly(memo)) {
                throw UnsafeWorkspaceMutationException("Unable to move memo to trash safely: ${memo.id}")
            }
            moveToTrashInDb(memo)
            memoVersionRecorder.enqueueLocalRevision(
                memo = memo.copy(isDeleted = true),
                lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.TRASHED,
                origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_TRASH,
            )
        }

        suspend fun moveToTrashInDb(memo: Memo) {
            memoWriteDao.deleteMemoById(memo.id)
            memoTagDao.deleteTagRefsByMemoId(memo.id)
            val trashProjection = MemoProjectionProjector.projectTrash(memo.copy(isDeleted = true))
            memoTrashDao.insertTrashMemo(trashProjection.entity)
            memoImageDao.replaceImageRefsForTrashMemo(trashProjection)
        }

        suspend fun moveToTrashFileOnly(memo: Memo): Boolean {
            requireSafeMemoDateKey(memo.dateKey)
            return workspaceStore.moveActiveMemoBlockToTrash(memo) == MemoWorkspaceBlockMutationResult.Applied
        }

        /**
         * Idempotent finisher used by the outbox retry path. Safe to invoke after a crash
         * between [moveToTrashFileOnly]'s main rewrite and its trash append: if the block is
         * already in the trash file under the command's memo identity, this is a no-op;
         * otherwise it appends the canonical source block so the deletion finishes durably.
         */
        internal suspend fun ensureMemoPresentInTrashFile(command: MemoLifecycleCommand): Boolean =
            workspaceStore.ensureTrashMemoBlock(command.sourceMemo) == MemoWorkspaceBlockMutationResult.Applied

        suspend fun restoreFromTrash(memo: Memo) {
            if (!restoreFromTrashFileOnly(memo)) {
                throw UnsafeWorkspaceMutationException("Unable to restore trash memo safely: ${memo.id}")
            }
            restoreFromTrashInDb(memo)
            memoVersionRecorder.enqueueLocalRevision(
                memo = memo.copy(isDeleted = false),
                lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.ACTIVE,
                origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_RESTORE,
            )
        }

        suspend fun restoreFromTrashInDb(memo: Memo): Boolean {
            val sourceMemo = memoTrashDao.getTrashMemo(memo.id)?.toDomain()?.copy(isDeleted = false) ?: return false
            persistRestoredMainMemo(memoWriteDao, memoTagDao, memoImageDao, sourceMemo)
            memoTrashDao.deleteTrashMemoById(sourceMemo.id)
            return true
        }

        suspend fun restoreFromTrashFileOnly(memo: Memo): Boolean {
            requireSafeMemoDateKey(memo.dateKey)
            val restored = workspaceStore.restoreTrashMemoBlockToActive(memo)
            if (restored != MemoWorkspaceBlockMutationResult.Applied) {
                Timber.e("restoreMemo: Failed to find memo block in trash file for ${memo.id}")
            }
            return restored == MemoWorkspaceBlockMutationResult.Applied
        }

        internal suspend fun deleteFromTrashFileOnly(
            command: MemoLifecycleCommand,
        ): PermanentDeleteTrashFileCompletion {
            require(command.operation == MemoLifecycleOperation.PERMANENT_DELETE) {
                "Trash permanent-delete file completion requires permanent-delete lifecycle command: " +
                    command.metadata.operationId.value
            }
            return deleteFromTrashFileOnly(command.sourceMemo)
        }

        private suspend fun deleteFromTrashFileOnly(memo: Memo): PermanentDeleteTrashFileCompletion {
            requireSafeMemoDateKey(memo.dateKey)
            return when (workspaceStore.removeTrashMemoBlock(memo)) {
                MemoWorkspaceBlockRemoval.Removed -> PermanentDeleteTrashFileCompletion.Completed
                is MemoWorkspaceBlockRemoval.MissingSourceSpan -> {
                    Timber.e("deletePermanently: Failed to find block for ${memo.id}")
                    PermanentDeleteTrashFileCompletion.MissingTrashBlock
                }
            }
        }

    }

internal sealed interface PermanentDeleteTrashFileCompletion {
    data object Completed : PermanentDeleteTrashFileCompletion

    data object MissingTrashBlock : PermanentDeleteTrashFileCompletion
}

private suspend fun persistRestoredMainMemo(
    memoWriteDao: MemoWriteDao,
    memoTagDao: MemoTagDao,
    memoImageDao: MemoImageDao,
    memo: Memo,
) {
    val projection = MemoProjectionProjector.projectActive(memo)
    memoWriteDao.insertMemo(projection.entity)
    memoTagDao.replaceTagRefsForMemo(projection)
    memoImageDao.replaceImageRefsForMemo(projection)
}
