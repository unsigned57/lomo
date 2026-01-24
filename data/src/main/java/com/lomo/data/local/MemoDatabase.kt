package com.lomo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lomo.data.local.dao.FileSyncDao
import com.lomo.data.local.dao.ImageCacheDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.FileSyncEntity
import com.lomo.data.local.entity.ImageCacheEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.MemoTagCrossRef
import com.lomo.data.local.entity.TagEntity

@Database(
    entities =
        [
            MemoEntity::class,
            ImageCacheEntity::class,
            TagEntity::class,
            MemoTagCrossRef::class,
            MemoFtsEntity::class,
            FileSyncEntity::class,
        ],
    version = 8,
    exportSchema = false,
)
abstract class MemoDatabase : RoomDatabase() {
    abstract fun memoDao(): MemoDao

    abstract fun imageCacheDao(): ImageCacheDao

    abstract fun fileSyncDao(): FileSyncDao
}
