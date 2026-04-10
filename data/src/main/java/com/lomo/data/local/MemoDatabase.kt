package com.lomo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoIdentityDao
import com.lomo.data.local.dao.MemoOutboxDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoVersionDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.dao.S3LocalChangeJournalDao
import com.lomo.data.local.dao.S3RemoteIndexDao
import com.lomo.data.local.dao.S3RemoteShardStateDao
import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncProtocolStateDao
import com.lomo.data.local.dao.WebDavSyncMetadataDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.MemoPinEntity
import com.lomo.data.local.entity.MemoRevisionAssetEntity
import com.lomo.data.local.entity.MemoRevisionEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.local.entity.MemoVersionBlobEntity
import com.lomo.data.local.entity.MemoVersionCommitEntity
import com.lomo.data.local.entity.PendingSyncConflictEntity
import com.lomo.data.local.entity.S3LocalChangeJournalEntity
import com.lomo.data.local.entity.S3RemoteIndexEntity
import com.lomo.data.local.entity.S3RemoteShardStateEntity
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.local.entity.S3SyncProtocolStateEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.local.entity.WebDavSyncMetadataEntity

const val MEMO_DATABASE_VERSION = 45

@Database(
    entities =
        [
            MemoEntity::class,
            TrashMemoEntity::class,
            MemoFtsEntity::class,
            MemoTagCrossRefEntity::class,
            LocalFileStateEntity::class,
            MemoFileOutboxEntity::class,
            MemoPinEntity::class,
            WebDavSyncMetadataEntity::class,
            S3SyncMetadataEntity::class,
            S3RemoteIndexEntity::class,
            S3RemoteShardStateEntity::class,
            S3SyncProtocolStateEntity::class,
            S3LocalChangeJournalEntity::class,
            MemoVersionCommitEntity::class,
            MemoVersionBlobEntity::class,
            MemoRevisionEntity::class,
            MemoRevisionAssetEntity::class,
            PendingSyncConflictEntity::class,
        ],
    version = MEMO_DATABASE_VERSION,
    exportSchema = true,
)
abstract class MemoDatabase : RoomDatabase() {
    abstract fun memoDao(): MemoDao

    abstract fun memoPinDao(): MemoPinDao

    abstract fun memoSearchDao(): MemoSearchDao

    abstract fun memoWriteDao(): MemoWriteDao

    abstract fun memoTagDao(): MemoTagDao

    abstract fun memoFtsDao(): MemoFtsDao

    abstract fun memoIdentityDao(): MemoIdentityDao

    abstract fun memoTrashDao(): MemoTrashDao

    abstract fun memoOutboxDao(): MemoOutboxDao

    abstract fun memoVersionDao(): MemoVersionDao

    abstract fun localFileStateDao(): LocalFileStateDao

    abstract fun webDavSyncMetadataDao(): WebDavSyncMetadataDao

    abstract fun s3SyncMetadataDao(): S3SyncMetadataDao

    abstract fun s3RemoteIndexDao(): S3RemoteIndexDao

    abstract fun s3RemoteShardStateDao(): S3RemoteShardStateDao

    abstract fun s3SyncProtocolStateDao(): S3SyncProtocolStateDao

    abstract fun s3LocalChangeJournalDao(): S3LocalChangeJournalDao

    abstract fun pendingSyncConflictDao(): PendingSyncConflictDao
}
