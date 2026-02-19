package com.lomo.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "memo_token",
    primaryKeys = ["token", "memoId"],
    indices = [Index(value = ["token"]), Index(value = ["memoId"])],
)
data class MemoTokenEntity(
    val token: String,
    val memoId: String,
)

