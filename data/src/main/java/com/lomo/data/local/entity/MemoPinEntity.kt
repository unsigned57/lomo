package com.lomo.data.local.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "MemoPin",
    indices = [Index(value = ["pinnedAt"])],
)
data class MemoPinEntity(
    @PrimaryKey val memoId: String,
    val pinnedAt: Long,
)
