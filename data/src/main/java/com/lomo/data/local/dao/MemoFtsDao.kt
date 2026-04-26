package com.lomo.data.local.dao

data class MemoFtsHealthReport(
    val tableUsesExternalContent: Boolean,
    val triggersPresent: Boolean,
    val contentVersion: Int,
)

interface MemoFtsDao {
    suspend fun rebuildFts()

    suspend fun optimizeFts()

    suspend fun clearFts()

    suspend fun integrityCheck(): String

    suspend fun healthCheck(): MemoFtsHealthReport
}
