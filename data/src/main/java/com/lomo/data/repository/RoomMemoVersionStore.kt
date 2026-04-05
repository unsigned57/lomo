package com.lomo.data.repository

import com.lomo.data.local.dao.MemoVersionDao
import com.lomo.data.local.dao.MemoRevisionHistoryRow
import com.lomo.data.local.entity.MemoRevisionAssetEntity
import com.lomo.data.local.entity.MemoRevisionEntity
import com.lomo.data.local.entity.MemoVersionBlobEntity
import com.lomo.data.local.entity.MemoVersionCommitEntity
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomMemoVersionStore
    @Inject
    constructor(
        private val memoVersionDao: MemoVersionDao,
    ) : MemoVersionStore {
        override suspend fun insertCommit(record: MemoVersionCommitRecord) {
            memoVersionDao.insertCommit(record.toEntity())
        }

        override suspend fun insertRevision(record: MemoVersionRevisionRecord) {
            memoVersionDao.insertRevision(record.toEntity())
        }

        override suspend fun replaceAssets(
            revisionId: String,
            records: List<MemoVersionAssetRecord>,
        ) {
            memoVersionDao.replaceAssets(revisionId, records.map(MemoVersionAssetRecord::toEntity))
        }

        override suspend fun getBlob(blobHash: String): MemoVersionBlobRecord? =
            memoVersionDao.getBlob(blobHash)?.toRecord()

        override suspend fun insertBlob(record: MemoVersionBlobRecord) {
            memoVersionDao.insertBlob(record.toEntity())
        }

        override suspend fun getRevision(revisionId: String): MemoVersionRevisionRecord? =
            memoVersionDao.getRevision(revisionId)?.toRecord()

        override suspend fun getCommit(commitId: String): MemoVersionCommitRecord? =
            memoVersionDao.getCommit(commitId)?.toRecord()

        override suspend fun listRevisionHistoryForMemo(
            memoId: String,
            cursor: MemoRevisionCursor?,
            limit: Int,
        ): List<MemoVersionRevisionHistoryRecord> =
            memoVersionDao
                .listRevisionHistoryForMemo(
                    memoId = memoId,
                    cursorCreatedAt = cursor?.createdAt,
                    cursorRevisionId = cursor?.revisionId,
                    limit = limit,
                ).map(MemoRevisionHistoryRow::toRecord)

        override suspend fun getLatestRevisionForMemo(memoId: String): MemoVersionRevisionRecord? =
            memoVersionDao.getLatestRevisionForMemo(memoId)?.toRecord()

    override suspend fun findEquivalentRevisionsForMemo(
        memoId: String,
        lifecycleState: MemoRevisionLifecycleState,
        rawMarkdownBlobHash: String,
        contentHash: String,
        assetFingerprint: String,
    ): List<MemoVersionRevisionRecord> =
            memoVersionDao
                .listEquivalentRevisionsForMemo(
                    memoId = memoId,
                    lifecycleState = lifecycleState.name,
                    rawMarkdownBlobHash = rawMarkdownBlobHash,
                    contentHash = contentHash,
                    assetFingerprint = assetFingerprint,
                ).map(MemoRevisionEntity::toRecord)

        override suspend fun listAssetsForRevision(revisionId: String): List<MemoVersionAssetRecord> =
            memoVersionDao.listAssetsForRevision(revisionId).map(MemoRevisionAssetEntity::toRecord)

    override suspend fun listStaleRevisionsForMemo(
        memoId: String,
        retainCount: Int,
        olderThanCreatedAt: Long?,
    ): List<MemoVersionRevisionRecord> =
            memoVersionDao.listStaleRevisionsForMemo(
                memoId = memoId,
                retainCount = retainCount,
                olderThanCreatedAt = olderThanCreatedAt,
            ).map(
                MemoRevisionEntity::toRecord,
            )

        override suspend fun listAllRevisionsForMemo(memoId: String): List<MemoVersionRevisionRecord> =
            memoVersionDao.listAllRevisionsForMemo(memoId).map(MemoRevisionEntity::toRecord)

        override suspend fun listAssetsForRevisionIds(revisionIds: List<String>): List<MemoVersionAssetRecord> =
            if (revisionIds.isEmpty()) {
                emptyList()
            } else {
                memoVersionDao.listAssetsForRevisionIds(revisionIds).map(MemoRevisionAssetEntity::toRecord)
            }

        override suspend fun deleteAssetsByRevisionIds(revisionIds: List<String>) {
            if (revisionIds.isNotEmpty()) {
                memoVersionDao.deleteAssetsByRevisionIds(revisionIds)
            }
        }

        override suspend fun deleteRevisionsByIds(revisionIds: List<String>) {
            if (revisionIds.isNotEmpty()) {
                memoVersionDao.deleteRevisionsByIds(revisionIds)
            }
        }

        override suspend fun isBlobReferenced(blobHash: String): Boolean = memoVersionDao.isBlobReferenced(blobHash)

        override suspend fun deleteBlob(blobHash: String) {
            memoVersionDao.deleteBlob(blobHash)
        }

        override suspend fun clearAll() {
            memoVersionDao.clearAllVersionHistory()
        }
    }

private fun MemoVersionCommitRecord.toEntity(): MemoVersionCommitEntity =
    MemoVersionCommitEntity(
        commitId = commitId,
        createdAt = createdAt,
        origin = origin.name,
        actor = actor,
        batchId = batchId,
        summary = summary,
    )

private fun MemoVersionCommitEntity.toRecord(): MemoVersionCommitRecord =
    MemoVersionCommitRecord(
        commitId = commitId,
        createdAt = createdAt,
        origin = MemoRevisionOrigin.valueOf(origin),
        actor = actor,
        batchId = batchId,
        summary = summary,
    )

private fun MemoVersionBlobRecord.toEntity(): MemoVersionBlobEntity =
    MemoVersionBlobEntity(
        blobHash = blobHash,
        storagePath = storagePath,
        byteSize = byteSize,
        contentEncoding = contentEncoding,
        createdAt = createdAt,
    )

private fun MemoVersionBlobEntity.toRecord(): MemoVersionBlobRecord =
    MemoVersionBlobRecord(
        blobHash = blobHash,
        storagePath = storagePath,
        byteSize = byteSize,
        contentEncoding = contentEncoding,
        createdAt = createdAt,
    )

private fun MemoVersionRevisionRecord.toEntity(): MemoRevisionEntity =
    MemoRevisionEntity(
        revisionId = revisionId,
        memoId = memoId,
        parentRevisionId = parentRevisionId,
        commitId = commitId,
        dateKey = dateKey,
        lifecycleState = lifecycleState.name,
        rawMarkdownBlobHash = rawMarkdownBlobHash,
        contentHash = contentHash,
        assetFingerprint = assetFingerprint,
        memoTimestamp = memoTimestamp,
        memoUpdatedAt = memoUpdatedAt,
        memoContent = memoContent,
        createdAt = createdAt,
    )

private fun MemoRevisionEntity.toRecord(): MemoVersionRevisionRecord =
    MemoVersionRevisionRecord(
        revisionId = revisionId,
        memoId = memoId,
        parentRevisionId = parentRevisionId,
        commitId = commitId,
        dateKey = dateKey,
        lifecycleState = MemoRevisionLifecycleState.valueOf(lifecycleState),
        rawMarkdownBlobHash = rawMarkdownBlobHash,
        contentHash = contentHash,
        assetFingerprint = assetFingerprint,
        memoTimestamp = memoTimestamp,
        memoUpdatedAt = memoUpdatedAt,
        memoContent = memoContent,
        createdAt = createdAt,
    )

private fun MemoRevisionHistoryRow.toRecord(): MemoVersionRevisionHistoryRecord =
    MemoVersionRevisionHistoryRecord(
        revisionId = revisionId,
        parentRevisionId = parentRevisionId,
        memoId = memoId,
        commitId = commitId,
        batchId = batchId,
        origin = MemoRevisionOrigin.valueOf(origin),
        summary = summary,
        lifecycleState = MemoRevisionLifecycleState.valueOf(lifecycleState),
        memoContent = memoContent,
        contentHash = contentHash,
        createdAt = createdAt,
    )

private fun MemoVersionAssetRecord.toEntity(): MemoRevisionAssetEntity =
    MemoRevisionAssetEntity(
        revisionId = revisionId,
        logicalPath = logicalPath,
        blobHash = blobHash,
        contentEncoding = contentEncoding,
    )

private fun MemoRevisionAssetEntity.toRecord(): MemoVersionAssetRecord =
    MemoVersionAssetRecord(
        revisionId = revisionId,
        logicalPath = logicalPath,
        blobHash = blobHash,
        contentEncoding = contentEncoding,
    )
