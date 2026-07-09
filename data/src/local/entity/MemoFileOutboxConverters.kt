package com.lomo.data.local.entity

import androidx.room3.TypeConverter

class MemoFileOutboxConverters {
    @TypeConverter
    fun fromOperation(operation: MemoFileOutboxOp): Int = operation.persistedValue

    @TypeConverter
    fun toOperation(value: Int): MemoFileOutboxOp = MemoFileOutboxOp.fromPersistedValue(value)
}
