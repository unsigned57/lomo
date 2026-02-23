package com.lomo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.local.entity.TrashMemoEntity

@Database(
    entities =
        [
            MemoEntity::class,
            TrashMemoEntity::class,
            MemoFtsEntity::class,
            MemoTagCrossRefEntity::class,
            LocalFileStateEntity::class,
        ],
    version = 20,
    exportSchema = false,
)
abstract class MemoDatabase : RoomDatabase() {
    abstract fun memoDao(): MemoDao

    abstract fun localFileStateDao(): LocalFileStateDao
}
