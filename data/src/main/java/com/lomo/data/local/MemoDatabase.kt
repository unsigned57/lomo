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
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.local.entity.WebDavSyncMetadataEntity

const val MEMO_DATABASE_VERSION = 31

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
            MemoVersionCommitEntity::class,
            MemoVersionBlobEntity::class,
            MemoRevisionEntity::class,
            MemoRevisionAssetEntity::class,
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
}
