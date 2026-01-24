package com.lomo.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4

@Entity(tableName = "lomo_fts")
@Fts4(tokenizer = "unicode61")
data class MemoFtsEntity(
    val memoId: String,
    val content: String,
)
