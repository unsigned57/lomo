package com.lomo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.local.entity.TrashMemoEntity

const val MEMO_DATABASE_VERSION = 23

@Database(
    entities =
        [
            MemoEntity::class,
            TrashMemoEntity::class,
            MemoFtsEntity::class,
            MemoTagCrossRefEntity::class,
            LocalFileStateEntity::class,
            MemoFileOutboxEntity::class,
        ],
    version = MEMO_DATABASE_VERSION,
    exportSchema = true,
)
abstract class MemoDatabase : RoomDatabase() {
    abstract fun memoDao(): MemoDao

    abstract fun localFileStateDao(): LocalFileStateDao
}
