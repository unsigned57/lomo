package com.lomo.data.local

import androidx.sqlite.SQLiteConnection
import com.lomo.data.local.entity.StoredMemoRecovery
import com.lomo.data.util.SearchTokenizer
import com.lomo.domain.usecase.MemoContentAnalyzer

private const val MEMO_ID_COLUMN_INDEX = 0
private const val MEMO_TIMESTAMP_COLUMN_INDEX = 1
private const val MEMO_CONTENT_COLUMN_INDEX = 2
private const val MEMO_RAW_CONTENT_COLUMN_INDEX = 3
private const val MEMO_DATE_COLUMN_INDEX = 4
internal const val COLUMN_HAS_TODO = "hasTodo"
internal const val COLUMN_HAS_ATTACHMENT = "hasAttachment"
internal const val COLUMN_HAS_URL = "hasUrl"
internal const val COLUMN_STATISTICS_WORD_COUNT = "statisticsWordCount"
internal const val COLUMN_STATISTICS_CHARACTER_COUNT = "statisticsCharacterCount"

internal fun backfillMemoSearchContentTokens(db: SQLiteConnection) {
    if (!db.tableExists(MEMO_TABLE)) {
        return
    }

    val pendingUpdates = mutableListOf<Pair<String, String>>()
    val cursor =
        db.query(
            """
            SELECT `id`, `$COLUMN_TIMESTAMP`, `$COLUMN_CONTENT`, `$COLUMN_RAW_CONTENT`, `date`
            FROM `$MEMO_TABLE`
            """.trimIndent(),
        )
    try {
        if (!cursor.moveToFirst()) {
            return
        }
        do {
            val memoId =
                requireNotNull(cursor.getString(MEMO_ID_COLUMN_INDEX)) {
                    "Legacy memo id missing during content backfill"
                }
            val storedTimestamp = cursor.getLong(MEMO_TIMESTAMP_COLUMN_INDEX)
            val storedContent =
                requireNotNull(cursor.getString(MEMO_CONTENT_COLUMN_INDEX)) {
                    "Legacy memo content missing during content backfill"
                }
            val rawContent =
                requireNotNull(cursor.getString(MEMO_RAW_CONTENT_COLUMN_INDEX)) {
                    "Legacy memo raw content missing during content backfill"
                }
            val dateKey =
                requireNotNull(cursor.getString(MEMO_DATE_COLUMN_INDEX)) {
                    "Legacy memo date missing during content backfill"
                }
            val visibleContent =
                StoredMemoRecovery.recoverOrNull(
                    rawContent = rawContent,
                    storedContent = storedContent,
                    storedTimestamp = storedTimestamp,
                    dateKey = dateKey,
                )?.content ?: storedContent
            if (visibleContent != storedContent) {
                pendingUpdates += visibleContent to memoId
            }
        } while (cursor.moveToNext())
    } finally {
        cursor.close()
    }

    if (pendingUpdates.isNotEmpty()) {
        db.usePreparedBatch(
            sql = "UPDATE `$MEMO_TABLE` SET `$COLUMN_CONTENT` = ? WHERE `id` = ?",
            items = pendingUpdates,
        ) { statement, (content, id) ->
            statement.bindText(1, content)
            statement.bindText(2, id)
            statement.step()
        }
    }
}

internal fun ensureMemoSearchContentColumn(db: SQLiteConnection) {
    if (!db.tableExists(MEMO_TABLE)) {
        return
    }
    if (COLUMN_SEARCH_CONTENT !in db.tableColumns(MEMO_TABLE)) {
        db.execSQL("ALTER TABLE `$MEMO_TABLE` ADD COLUMN `$COLUMN_SEARCH_CONTENT` TEXT NOT NULL DEFAULT ''")
    }
}

internal fun backfillMemoSearchContentColumn(db: SQLiteConnection) {
    if (!db.tableExists(MEMO_TABLE)) {
        return
    }
    ensureMemoSearchContentColumn(db)

    val pendingSearchUpdates = mutableListOf<Pair<String, String>>()
    db.query("SELECT `id`, `$COLUMN_CONTENT`, `$COLUMN_SEARCH_CONTENT` FROM `$MEMO_TABLE`").use { cursor ->
        val idIndex = cursor.getColumnIndex("id")
        val contentIndex = cursor.getColumnIndex(COLUMN_CONTENT)
        val searchContentIndex = cursor.getColumnIndex(COLUMN_SEARCH_CONTENT)
        while (cursor.moveToNext()) {
            val memoId =
                requireNotNull(cursor.getString(idIndex)) {
                    "Legacy memo id missing during search content backfill"
                }
            val content =
                requireNotNull(cursor.getString(contentIndex)) {
                    "Legacy memo content missing during search content backfill"
                }
            val searchContent =
                requireNotNull(cursor.getString(searchContentIndex)) {
                    "Legacy memo search content missing during search content backfill"
                }
            val tokenizedContent = SearchTokenizer.tokenize(content)
            if (tokenizedContent != searchContent) {
                pendingSearchUpdates += tokenizedContent to memoId
            }
        }
    }
    if (pendingSearchUpdates.isNotEmpty()) {
        db.usePreparedBatch(
            sql = "UPDATE `$MEMO_TABLE` SET `$COLUMN_SEARCH_CONTENT` = ? WHERE `id` = ?",
            items = pendingSearchUpdates,
        ) { statement, (tokenized, id) ->
            statement.bindText(1, tokenized)
            statement.bindText(2, id)
            statement.step()
        }
    }
}

internal fun ensureMemoContentFlagColumns(db: SQLiteConnection) {
    if (!db.tableExists(MEMO_TABLE)) {
        return
    }
    val columns = db.tableColumns(MEMO_TABLE)
    if (COLUMN_HAS_TODO !in columns) {
        db.execSQL("ALTER TABLE `$MEMO_TABLE` ADD COLUMN `$COLUMN_HAS_TODO` INTEGER NOT NULL DEFAULT 0")
    }
    if (COLUMN_HAS_ATTACHMENT !in columns) {
        db.execSQL("ALTER TABLE `$MEMO_TABLE` ADD COLUMN `$COLUMN_HAS_ATTACHMENT` INTEGER NOT NULL DEFAULT 0")
    }
    if (COLUMN_HAS_URL !in columns) {
        db.execSQL("ALTER TABLE `$MEMO_TABLE` ADD COLUMN `$COLUMN_HAS_URL` INTEGER NOT NULL DEFAULT 0")
    }
    createMemoContentFlagIndexes(db)
}

internal fun backfillMemoContentFlagColumns(db: SQLiteConnection) {
    if (!db.tableExists(MEMO_TABLE)) {
        return
    }
    ensureMemoContentFlagColumns(db)

    val pendingFlagUpdates = mutableListOf<MemoContentFlagUpdate>()
    db.query(
        """
        SELECT `id`, `$COLUMN_CONTENT`, `$COLUMN_HAS_TODO`, `$COLUMN_HAS_ATTACHMENT`, `$COLUMN_HAS_URL`
        FROM `$MEMO_TABLE`
        """.trimIndent(),
    ).use { cursor ->
        val idIndex = cursor.getColumnIndex("id")
        val contentIndex = cursor.getColumnIndex(COLUMN_CONTENT)
        val hasTodoIndex = cursor.getColumnIndex(COLUMN_HAS_TODO)
        val hasAttachmentIndex = cursor.getColumnIndex(COLUMN_HAS_ATTACHMENT)
        val hasUrlIndex = cursor.getColumnIndex(COLUMN_HAS_URL)
        while (cursor.moveToNext()) {
            val memoId =
                requireNotNull(cursor.getString(idIndex)) {
                    "Legacy memo id missing during content flag backfill"
                }
            val content =
                requireNotNull(cursor.getString(contentIndex)) {
                    "Legacy memo content missing during content flag backfill"
                }
            val analysis = MemoContentAnalyzer.analyze(content)
            if (
                cursor.getInt(hasTodoIndex).toBoolean() != analysis.hasTodo ||
                cursor.getInt(hasAttachmentIndex).toBoolean() != analysis.hasAttachment ||
                cursor.getInt(hasUrlIndex).toBoolean() != analysis.hasUrl
            ) {
                pendingFlagUpdates +=
                    MemoContentFlagUpdate(
                        id = memoId,
                        hasTodo = analysis.hasTodo,
                        hasAttachment = analysis.hasAttachment,
                        hasUrl = analysis.hasUrl,
                    )
            }
        }
    }
    if (pendingFlagUpdates.isNotEmpty()) {
        db.usePreparedBatch(
            sql =
                """
                UPDATE `$MEMO_TABLE`
                SET `$COLUMN_HAS_TODO` = ?, `$COLUMN_HAS_ATTACHMENT` = ?, `$COLUMN_HAS_URL` = ?
                WHERE `id` = ?
                """.trimIndent(),
            items = pendingFlagUpdates,
        ) { statement, update ->
            statement.bindBoolean(MEMO_FLAG_BIND_HAS_TODO, update.hasTodo)
            statement.bindBoolean(MEMO_FLAG_BIND_HAS_ATTACHMENT, update.hasAttachment)
            statement.bindBoolean(MEMO_FLAG_BIND_HAS_URL, update.hasUrl)
            statement.bindText(MEMO_FLAG_BIND_ID, update.id)
            statement.step()
        }
    }
}

internal fun createMemoContentFlagIndexes(db: SQLiteConnection) {
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_${MEMO_TABLE}_$COLUMN_HAS_TODO` " +
            "ON `$MEMO_TABLE` (`$COLUMN_HAS_TODO`)",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_${MEMO_TABLE}_$COLUMN_HAS_ATTACHMENT` " +
            "ON `$MEMO_TABLE` (`$COLUMN_HAS_ATTACHMENT`)",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_${MEMO_TABLE}_$COLUMN_HAS_URL` " +
            "ON `$MEMO_TABLE` (`$COLUMN_HAS_URL`)",
    )
}

internal fun ensureMemoStatisticsProjectionColumns(db: SQLiteConnection) {
    if (!db.tableExists(MEMO_TABLE)) {
        return
    }
    val columns = db.tableColumns(MEMO_TABLE)
    if (COLUMN_STATISTICS_WORD_COUNT !in columns) {
        db.addMemoIntegerProjectionColumn(COLUMN_STATISTICS_WORD_COUNT)
    }
    if (COLUMN_STATISTICS_CHARACTER_COUNT !in columns) {
        db.addMemoIntegerProjectionColumn(COLUMN_STATISTICS_CHARACTER_COUNT)
    }
}

private fun SQLiteConnection.addMemoIntegerProjectionColumn(columnName: String) {
    execSQL("ALTER TABLE `$MEMO_TABLE` ADD COLUMN `$columnName` INTEGER NOT NULL DEFAULT 0")
}

internal fun backfillMemoStatisticsProjectionColumns(db: SQLiteConnection) {
    if (!db.tableExists(MEMO_TABLE)) {
        return
    }
    ensureMemoStatisticsProjectionColumns(db)

    val pendingUpdates = mutableListOf<MemoStatisticsProjectionUpdate>()
    db.query(
        """
        SELECT `id`, `$COLUMN_TIMESTAMP`, `$COLUMN_CONTENT`, `$COLUMN_STATISTICS_WORD_COUNT`,
            `$COLUMN_STATISTICS_CHARACTER_COUNT`
        FROM `$MEMO_TABLE`
        """.trimIndent(),
    ).use { cursor ->
        val idIndex = cursor.getColumnIndex("id")
        val timestampIndex = cursor.getColumnIndex(COLUMN_TIMESTAMP)
        val contentIndex = cursor.getColumnIndex(COLUMN_CONTENT)
        val wordCountIndex = cursor.getColumnIndex(COLUMN_STATISTICS_WORD_COUNT)
        val characterCountIndex = cursor.getColumnIndex(COLUMN_STATISTICS_CHARACTER_COUNT)
        while (cursor.moveToNext()) {
            val memoId =
                requireNotNull(cursor.getString(idIndex)) {
                    "Legacy memo id missing during statistics projection backfill"
                }
            val content =
                requireNotNull(cursor.getString(contentIndex)) {
                    "Legacy memo content missing during statistics projection backfill"
                }
            val projection =
                com.lomo.domain.model.MemoStatisticsCalculator.projectMemo(
                    timestamp = cursor.getLong(timestampIndex),
                    content = content,
                )
            if (
                cursor.getInt(wordCountIndex) != projection.wordCount ||
                cursor.getInt(characterCountIndex) != projection.characterCount
            ) {
                pendingUpdates +=
                    MemoStatisticsProjectionUpdate(
                        id = memoId,
                        wordCount = projection.wordCount,
                        characterCount = projection.characterCount,
                    )
            }
        }
    }
    if (pendingUpdates.isNotEmpty()) {
        db.usePreparedBatch(
            sql =
                """
                UPDATE `$MEMO_TABLE`
                SET `$COLUMN_STATISTICS_WORD_COUNT` = ?, `$COLUMN_STATISTICS_CHARACTER_COUNT` = ?
                WHERE `id` = ?
                """.trimIndent(),
            items = pendingUpdates,
        ) { statement, update ->
            statement.bindInt(MEMO_STATISTICS_BIND_WORD_COUNT, update.wordCount)
            statement.bindInt(MEMO_STATISTICS_BIND_CHARACTER_COUNT, update.characterCount)
            statement.bindText(MEMO_STATISTICS_BIND_ID, update.id)
            statement.step()
        }
    }
}

private data class MemoContentFlagUpdate(
    val id: String,
    val hasTodo: Boolean,
    val hasAttachment: Boolean,
    val hasUrl: Boolean,
)

private data class MemoStatisticsProjectionUpdate(
    val id: String,
    val wordCount: Int,
    val characterCount: Int,
)

private const val MEMO_FLAG_BIND_HAS_TODO = 1
private const val MEMO_FLAG_BIND_HAS_ATTACHMENT = 2
private const val MEMO_FLAG_BIND_HAS_URL = 3
private const val MEMO_FLAG_BIND_ID = 4
private const val MEMO_STATISTICS_BIND_WORD_COUNT = 1
private const val MEMO_STATISTICS_BIND_CHARACTER_COUNT = 2
private const val MEMO_STATISTICS_BIND_ID = 3

private fun Int.toBoolean(): Boolean = this != 0
