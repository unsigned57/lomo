package com.lomo.data.local

import androidx.sqlite.SQLiteConnection
import timber.log.Timber

internal fun ensureMemoFtsTable(db: SQLiteConnection) {
    ensureMemoFtsMaintenanceTable(db)
    backfillMemoSearchContentTokens(db)
    ensureMemoSearchContentColumn(db)

    val requiresSchemaRepair = !db.memoFtsTableUsesExternalContent() || !db.memoFtsExternalContentTriggersPresent()
    if (requiresSchemaRepair) {
        val repairStartedAt = System.currentTimeMillis()
        backfillMemoSearchContentColumn(db)
        rebuildMemoFtsExternalContentInfrastructure(db)
        db.setMemoFtsContentVersion(CURRENT_MEMO_FTS_CONTENT_VERSION)
        val repairDurationMs = System.currentTimeMillis() - repairStartedAt
        MemoFtsTelemetry.recordAutoRepair(repairDurationMs)
        Timber.tag(MEMO_FTS_TAG).w(
            "Auto-repaired memo FTS infrastructure in %d ms",
            repairDurationMs,
        )
        return
    }

    if (db.memoFtsContentVersion() < CURRENT_MEMO_FTS_CONTENT_VERSION) {
        backfillMemoSearchContentColumn(db)
        rebuildMemoFtsExternalContentIndex(db)
        db.setMemoFtsContentVersion(CURRENT_MEMO_FTS_CONTENT_VERSION)
    }
}

internal fun SQLiteConnection.memoFtsTableUsesExternalContent(): Boolean =
    query("SELECT sql FROM sqlite_master WHERE type='table' AND name='$FTS_TABLE' LIMIT 1").use { cursor ->
        if (!cursor.moveToFirst()) {
            return@use false
        }
        val createSql = cursor.getString(0).orEmpty()
        createSql.contains("fts5", ignoreCase = true) &&
            createSql.contains("content='Lomo'", ignoreCase = true) &&
            createSql.contains("content_rowid='rowid'", ignoreCase = true) &&
            createSql.contains(COLUMN_SEARCH_CONTENT, ignoreCase = true)
    }

internal fun ensureMemoFtsMaintenanceTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$MEMO_FTS_MAINTENANCE_TABLE` (
            `id` INTEGER NOT NULL,
            `content_version` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
}

internal fun SQLiteConnection.memoFtsContentVersion(): Int =
    query("SELECT `content_version` FROM `$MEMO_FTS_MAINTENANCE_TABLE` WHERE `id` = 1 LIMIT 1").use { cursor ->
        if (!cursor.moveToFirst()) {
            return@use 0
        }
        cursor.getInt(0)
    }

internal fun SQLiteConnection.setMemoFtsContentVersion(version: Int) {
    execSQL(
        "INSERT OR REPLACE INTO `$MEMO_FTS_MAINTENANCE_TABLE`(`id`, `content_version`) VALUES (1, ?)",
        arrayOf(version),
    )
}

internal data class MemoFtsHeavyHealthCheckResult(
    val lomoCount: Int,
    val ftsCount: Int,
    val sampledMatchCount: Int,
    val sampledExpectedCount: Int,
) {
    val isHealthy: Boolean
        get() = lomoCount == ftsCount && sampledMatchCount == sampledExpectedCount
}

internal fun runMemoFtsHeavyHealthCheck(db: SQLiteConnection): MemoFtsHeavyHealthCheckResult {
    if (!db.tableExists(MEMO_TABLE) || !db.tableExists(FTS_TABLE)) {
        return MemoFtsHeavyHealthCheckResult(
            lomoCount = 0,
            ftsCount = -1,
            sampledMatchCount = 0,
            sampledExpectedCount = 0,
        )
    }

    val lomoCount = db.query("SELECT COUNT(*) FROM `$MEMO_TABLE`").use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }
    val ftsCount = db.query("SELECT COUNT(*) FROM `$FTS_TABLE`").use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    val samples = mutableListOf<Pair<Long, String>>()
    db.query(
        "SELECT rowid, `$COLUMN_SEARCH_CONTENT` FROM `$MEMO_TABLE` " +
            "WHERE `$COLUMN_SEARCH_CONTENT` != '' LIMIT $HEAVY_CHECK_SAMPLE_LIMIT",
    ).use { cursor ->
        val rowIdIndex = cursor.getColumnIndex("rowid")
        val searchContentIndex = cursor.getColumnIndex(COLUMN_SEARCH_CONTENT)
        while (cursor.moveToNext()) {
            val rowId = cursor.getLong(rowIdIndex)
            val firstToken = cursor.getString(searchContentIndex).orEmpty().split(' ').firstOrNull().orEmpty()
            if (firstToken.isNotBlank()) {
                samples += rowId to firstToken
            }
        }
    }

    val sampledMatchCount =
        samples.count { (rowId, token) ->
            val matchQuery = '"' + token.replace("\"", "\"\"") + "\"*"
            db.query(
                "SELECT 1 FROM `$FTS_TABLE` WHERE rowid = ? AND `$FTS_TABLE` MATCH ? LIMIT 1",
                arrayOf<Any?>(rowId, matchQuery),
            ).use { cursor ->
                cursor.moveToFirst()
            }
        }

    return MemoFtsHeavyHealthCheckResult(
        lomoCount = lomoCount,
        ftsCount = ftsCount,
        sampledMatchCount = sampledMatchCount,
        sampledExpectedCount = samples.size,
    )
}

private const val MEMO_FTS_TAG = "MemoFtsMaintenance"
private const val MEMO_FTS_MAINTENANCE_TABLE = "lomo_fts_maintenance"
internal const val CURRENT_MEMO_FTS_CONTENT_VERSION = 3
private const val HEAVY_CHECK_SAMPLE_LIMIT = 8
