package com.lomo.data.local.dao
import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import com.lomo.data.local.CURRENT_MEMO_FTS_CONTENT_VERSION
import com.lomo.data.local.MemoDatabase
class RoomMemoFtsDao constructor(
    private val database: MemoDatabase,
) : MemoFtsDao {
    override suspend fun rebuildFts() {
        database.withWriteTransaction {
            usePrepared("INSERT INTO lomo_fts(lomo_fts) VALUES ('rebuild')") { statement ->
                statement.step()
            }
        }
    }
    override suspend fun optimizeFts() {
        database.withWriteTransaction {
            usePrepared("INSERT INTO lomo_fts(lomo_fts) VALUES ('optimize')") { statement ->
                statement.step()
            }
        }
    }
    override suspend fun clearFts() {
        database.withWriteTransaction {
            usePrepared("DELETE FROM lomo_fts") { statement ->
                statement.step()
            }
            usePrepared(
                "INSERT OR REPLACE INTO lomo_fts_maintenance(id, content_version) VALUES (1, ?)",
            ) { statement ->
                statement.bindInt(1, CURRENT_MEMO_FTS_CONTENT_VERSION)
                statement.step()
            }
        }
    }
    override suspend fun integrityCheck(): String =
        database.useReaderConnection { connection ->
            connection.usePrepared("PRAGMA integrity_check") { statement ->
                if (statement.step()) {
                    statement.getText(0).orEmpty()
                } else {
                    "unknown"
                }
            }
        }
    override suspend fun healthCheck(): MemoFtsHealthReport =
        database.useReaderConnection { connection ->
            MemoFtsHealthReport(
                tableUsesExternalContent =
                    connection.usePrepared(
                        "SELECT sql FROM sqlite_master WHERE type='table' AND name='lomo_fts' LIMIT 1",
                    ) { statement ->
                        if (!statement.step()) {
                            return@usePrepared false
                        }
                        val createSql = statement.getText(0).orEmpty()
                        createSql.contains("fts5", ignoreCase = true) &&
                            createSql.contains("content='Lomo'", ignoreCase = true) &&
                            createSql.contains("content_rowid='rowid'", ignoreCase = true) &&
                            createSql.contains("searchContent", ignoreCase = true)
                    },
                triggersPresent =
                    listOf("lomo_fts_ai", "lomo_fts_au", "lomo_fts_ad").all { triggerName ->
                        connection.usePrepared(
                            "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=? LIMIT 1",
                        ) { statement ->
                            statement.bindText(1, triggerName)
                            statement.step()
                        }
                    },
                contentVersion =
                    connection.usePrepared(
                        "SELECT `content_version` FROM `lomo_fts_maintenance` WHERE `id` = 1 LIMIT 1",
                    ) { statement ->
                        if (statement.step()) {
                            statement.getLong(0).toInt()
                        } else {
                            0
                        }
                    },
            )
        }
}
