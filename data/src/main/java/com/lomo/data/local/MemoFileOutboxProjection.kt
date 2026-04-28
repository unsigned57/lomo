package com.lomo.data.local

import androidx.sqlite.SQLiteConnection

internal data class MemoFileOutboxProjection(
    val idExpr: String,
    val operationExpr: String,
    val memoIdExpr: String,
    val memoDateExpr: String,
    val memoTimestampExpr: String,
    val memoRawContentExpr: String,
    val newContentExpr: String,
    val createRawContentExpr: String,
    val createdAtExpr: String,
    val updatedAtExpr: String,
    val retryCountExpr: String,
    val lastErrorExpr: String,
    val claimTokenExpr: String,
    val claimUpdatedAtExpr: String,
)

private fun pickMemoFileOutboxOperationExpr(columns: Set<String>): String {
    if ("operation" !in columns) {
        return UPDATE_OPERATION_SQL
    }
    return """
        CASE
            WHEN typeof(`operation`) = 'integer' THEN CAST(`operation` AS INTEGER)
            WHEN CAST(`operation` AS TEXT) = 'CREATE' THEN 0
            WHEN CAST(`operation` AS TEXT) = 'UPDATE' THEN 1
            WHEN CAST(`operation` AS TEXT) = 'DELETE' THEN 2
            WHEN CAST(`operation` AS TEXT) = 'RESTORE' THEN 3
            ELSE NULL
        END
    """.trimIndent()
}

internal fun pickTextExpr(
    columns: Set<String>,
    vararg candidates: String,
    defaultExpr: String = "''",
): String {
    val column = candidates.firstOrNull { it in columns } ?: return defaultExpr
    return "COALESCE(CAST(`$column` AS TEXT), $defaultExpr)"
}

internal fun pickNullableTextExpr(
    columns: Set<String>,
    vararg candidates: String,
): String {
    val column = candidates.firstOrNull { it in columns } ?: return "NULL"
    return "CAST(`$column` AS TEXT)"
}

internal fun pickIntExpr(
    columns: Set<String>,
    vararg candidates: String,
    defaultExpr: String = "0",
): String {
    val column = candidates.firstOrNull { it in columns } ?: return defaultExpr
    return "COALESCE(CAST(`$column` AS INTEGER), $defaultExpr)"
}

internal fun pickNullableIntExpr(
    columns: Set<String>,
    vararg candidates: String,
): String {
    val column = candidates.firstOrNull { it in columns } ?: return "NULL"
    return "CAST(`$column` AS INTEGER)"
}

internal fun insertLegacyMemoRows(
    db: SQLiteConnection,
    targetTable: String,
    sourceTable: String,
    idExpr: String,
    timestampExpr: String,
    contentExpr: String,
    rawContentExpr: String,
    dateExpr: String,
    tagsExpr: String,
    imageUrlsExpr: String,
    whereClause: String = "",
) {
    db.execSQL(
        """
        INSERT OR REPLACE INTO `$targetTable` (
            `id`, `$COLUMN_TIMESTAMP`, `$COLUMN_CONTENT`, `$COLUMN_RAW_CONTENT`, `date`, `tags`, `imageUrls`
        )
        SELECT
            $idExpr,
            $timestampExpr,
            $contentExpr,
            $rawContentExpr,
            $dateExpr,
            $tagsExpr,
            $imageUrlsExpr
        FROM `$sourceTable`
        $whereClause
        """.trimIndent(),
    )
}

internal fun legacyMemoIdExpr(columns: Set<String>): String =
    if ("id" in columns) {
        "COALESCE(CAST(`id` AS TEXT), $LEGACY_ROW_ID_TEXT_EXPR)"
    } else {
        LEGACY_ROW_ID_TEXT_EXPR
    }

internal fun memoFileOutboxProjection(columns: Set<String>): MemoFileOutboxProjection =
    MemoFileOutboxProjection(
        idExpr = pickNullableIntExpr(columns, "id"),
        operationExpr = pickMemoFileOutboxOperationExpr(columns),
        memoIdExpr = pickTextExpr(columns, "memoId", "id"),
        memoDateExpr = pickTextExpr(columns, "memoDate", "date"),
        memoTimestampExpr = pickIntExpr(columns, "memoTimestamp", COLUMN_TIMESTAMP),
        memoRawContentExpr = pickTextExpr(columns, "memoRawContent", COLUMN_RAW_CONTENT, COLUMN_CONTENT),
        newContentExpr = pickNullableTextExpr(columns, "newContent"),
        createRawContentExpr = pickNullableTextExpr(columns, "createRawContent"),
        createdAtExpr = pickIntExpr(columns, "createdAt", defaultExpr = CURRENT_TIME_MILLIS_SQL),
        updatedAtExpr = pickIntExpr(columns, "updatedAt", "createdAt", defaultExpr = CURRENT_TIME_MILLIS_SQL),
        retryCountExpr = pickIntExpr(columns, "retryCount"),
        lastErrorExpr = pickNullableTextExpr(columns, "lastError"),
        claimTokenExpr = pickNullableTextExpr(columns, "claimToken"),
        claimUpdatedAtExpr = pickNullableIntExpr(columns, "claimUpdatedAt"),
    )
