package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lomo.data.local.entity.MemoRevisionAssetEntity
import com.lomo.data.local.entity.MemoRevisionEntity
import com.lomo.data.local.entity.MemoVersionBlobEntity
import com.lomo.data.local.entity.MemoVersionCommitEntity

data class MemoRevisionHistoryRow(
    val revisionId: String,
    val parentRevisionId: String?,
    val memoId: String,
    val commitId: String,
    val batchId: String?,
    val origin: String,
    val summary: String,
    val lifecycleState: String,
    val memoContent: String,
    val contentHash: String,
    val createdAt: Long,
)

interface MemoVersionCommitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommit(entity: MemoVersionCommitEntity)

    @Query("SELECT * FROM version_commit WHERE commitId = :commitId LIMIT 1")
    suspend fun getCommit(commitId: String): MemoVersionCommitEntity?
}

interface MemoVersionRevisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRevision(entity: MemoRevisionEntity)

    @Query("SELECT * FROM memo_revision WHERE revisionId = :revisionId LIMIT 1")
    suspend fun getRevision(revisionId: String): MemoRevisionEntity?

    @Query(
        """
        SELECT
            r.revisionId AS revisionId,
            r.parentRevisionId AS parentRevisionId,
            r.memoId AS memoId,
            r.commitId AS commitId,
            c.batchId AS batchId,
            c.origin AS origin,
            c.summary AS summary,
            r.lifecycleState AS lifecycleState,
            r.memoContent AS memoContent,
            r.contentHash AS contentHash,
            r.createdAt AS createdAt
        FROM memo_revision AS r
        INNER JOIN version_commit AS c ON c.commitId = r.commitId
        WHERE r.memoId = :memoId
          AND (
            :cursorCreatedAt IS NULL OR
            r.createdAt < :cursorCreatedAt OR
            (r.createdAt = :cursorCreatedAt AND r.revisionId < :cursorRevisionId)
          )
        ORDER BY r.createdAt DESC, r.revisionId DESC
        LIMIT :limit
        """,
    )
    suspend fun listRevisionHistoryForMemo(
        memoId: String,
        cursorCreatedAt: Long?,
        cursorRevisionId: String?,
        limit: Int,
    ): List<MemoRevisionHistoryRow>

    @Query(
        """
        SELECT * FROM memo_revision
        WHERE memoId = :memoId
        ORDER BY createdAt DESC, revisionId DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestRevisionForMemo(memoId: String): MemoRevisionEntity?

    @Query(
        """
        SELECT * FROM memo_revision
        WHERE memoId = :memoId
          AND lifecycleState = :lifecycleState
          AND rawMarkdownBlobHash = :rawMarkdownBlobHash
          AND contentHash = :contentHash
          AND (assetFingerprint = :assetFingerprint OR assetFingerprint IS NULL)
        ORDER BY createdAt DESC, revisionId DESC
        """,
    )
    suspend fun listEquivalentRevisionsForMemo(
        memoId: String,
        lifecycleState: String,
        rawMarkdownBlobHash: String,
        contentHash: String,
        assetFingerprint: String,
    ): List<MemoRevisionEntity>

    @Query(
        """
        SELECT DISTINCT * FROM memo_revision
        WHERE memoId = :memoId
          AND (
            (:retainCount > 0 AND revisionId IN (
                SELECT revisionId FROM memo_revision
                WHERE memoId = :memoId
                ORDER BY createdAt DESC, revisionId DESC
                LIMIT -1 OFFSET :retainCount
            )) OR
            (:olderThanCreatedAt IS NOT NULL AND createdAt < :olderThanCreatedAt)
          )
        ORDER BY createdAt DESC, revisionId DESC
        """,
    )
    suspend fun listStaleRevisionsForMemo(
        memoId: String,
        retainCount: Int,
        olderThanCreatedAt: Long?,
    ): List<MemoRevisionEntity>

    @Query(
        """
        SELECT * FROM memo_revision
        WHERE memoId = :memoId
        ORDER BY createdAt DESC, revisionId DESC
        """,
    )
    suspend fun listAllRevisionsForMemo(memoId: String): List<MemoRevisionEntity>

    @Query("DELETE FROM memo_revision WHERE revisionId IN (:revisionIds)")
    suspend fun deleteRevisionsByIds(revisionIds: List<String>)

    @Query("DELETE FROM memo_revision")
    suspend fun deleteAllRevisions()
}

interface MemoVersionAssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssets(entities: List<MemoRevisionAssetEntity>)

    @Query("DELETE FROM memo_revision_asset WHERE revisionId = :revisionId")
    suspend fun deleteAssetsByRevisionId(revisionId: String)

    @Transaction
    suspend fun replaceAssets(
        revisionId: String,
        entities: List<MemoRevisionAssetEntity>,
    ) {
        deleteAssetsByRevisionId(revisionId)
        if (entities.isNotEmpty()) {
            insertAssets(entities)
        }
    }

    @Query(
        """
        SELECT * FROM memo_revision_asset
        WHERE revisionId = :revisionId
        ORDER BY logicalPath ASC
        """,
    )
    suspend fun listAssetsForRevision(revisionId: String): List<MemoRevisionAssetEntity>

    @Query("SELECT * FROM memo_revision_asset WHERE revisionId IN (:revisionIds)")
    suspend fun listAssetsForRevisionIds(revisionIds: List<String>): List<MemoRevisionAssetEntity>

    @Query("DELETE FROM memo_revision_asset WHERE revisionId IN (:revisionIds)")
    suspend fun deleteAssetsByRevisionIds(revisionIds: List<String>)

    @Query("DELETE FROM memo_revision_asset")
    suspend fun deleteAllAssets()
}

interface MemoVersionBlobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlob(entity: MemoVersionBlobEntity)

    @Query("SELECT * FROM memo_version_blob WHERE blobHash = :blobHash LIMIT 1")
    suspend fun getBlob(blobHash: String): MemoVersionBlobEntity?

    @Query(
        """
        SELECT CASE
            WHEN EXISTS(SELECT 1 FROM memo_revision WHERE rawMarkdownBlobHash = :blobHash LIMIT 1)
              OR EXISTS(SELECT 1 FROM memo_revision_asset WHERE blobHash = :blobHash LIMIT 1)
            THEN 1 ELSE 0 END
        """,
    )
    suspend fun isBlobReferenced(blobHash: String): Boolean

    @Query("DELETE FROM memo_version_blob WHERE blobHash = :blobHash")
    suspend fun deleteBlob(blobHash: String)

    @Query("DELETE FROM memo_version_blob")
    suspend fun deleteAllBlobs()
}

@Dao
interface MemoVersionDao :
    MemoVersionCommitDao,
    MemoVersionRevisionDao,
    MemoVersionAssetDao,
    MemoVersionBlobDao {
    @Query("DELETE FROM version_commit")
    suspend fun deleteAllCommits()

    @Transaction
    suspend fun clearAllVersionHistory() {
        deleteAllAssets()
        deleteAllRevisions()
        deleteAllBlobs()
        deleteAllCommits()
    }
}
