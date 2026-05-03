package com.lomo.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

internal fun SQLiteConnection.tableExists(tableName: String): Boolean =
    query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='$tableName' LIMIT 1").use { cursor ->
        cursor.moveToFirst()
    }

internal fun SQLiteConnection.tableCreateSql(tableName: String): String? =
    query("SELECT sql FROM sqlite_master WHERE type='table' AND name='$tableName' LIMIT 1").use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)
        } else {
            null
        }
    }

internal fun SQLiteConnection.tableColumns(tableName: String): Set<String> =
    query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val columns = linkedSetOf<String>()
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (nameIndex >= 0) {
                cursor.getString(nameIndex)?.let(columns::add)
            }
        }
        columns
    }

internal fun SQLiteConnection.dropExplicitIndices(tableName: String) {
    val indices =
        query("PRAGMA index_list(`$tableName`)").use { cursor ->
            val names = mutableListOf<String>()
            val nameIndex = cursor.getColumnIndex("name")
            val originIndex = cursor.getColumnIndex("origin")
            while (cursor.moveToNext()) {
                val origin = if (originIndex >= 0) cursor.getString(originIndex) else null
                if (origin == "c" || origin == null) {
                    cursor.getString(nameIndex)?.let(names::add)
                }
            }
            names
        }
    indices.forEach { indexName ->
        execSQL("DROP INDEX IF EXISTS `$indexName`")
    }
}

internal class SQLiteQueryCursor(
    private val statement: SQLiteStatement,
) : AutoCloseable {
    private val columnNames: List<String> by lazy { statement.getColumnNames() }
    private var started = false

    fun moveToFirst(): Boolean {
        if (started) {
            statement.reset()
        }
        started = true
        return statement.step()
    }

    fun moveToNext(): Boolean {
        if (!started) {
            return moveToFirst()
        }
        return statement.step()
    }

    fun getString(index: Int): String? = if (statement.isNull(index)) null else statement.getText(index)

    fun getLong(index: Int): Long = statement.getLong(index)

    fun getInt(index: Int): Int = statement.getInt(index)

    fun getColumnIndex(name: String): Int = columnNames.indexOf(name)

    override fun close() {
        statement.close()
    }
}

internal fun SQLiteConnection.query(sql: String): SQLiteQueryCursor = SQLiteQueryCursor(statement = prepare(sql))

internal fun SQLiteConnection.query(
    sql: String,
    bindArgs: Array<out Any?>,
): SQLiteQueryCursor =
    SQLiteQueryCursor(
        statement =
            prepare(sql).also { statement ->
                bindArgs.forEachIndexed { index, arg ->
                    statement.bindSqlValue(index = index + 1, value = arg)
                }
            },
    )

internal fun SQLiteConnection.execSQL(
    sql: String,
    bindArgs: Array<out Any?> = emptyArray(),
) {
    prepare(sql).use { statement ->
        bindArgs.forEachIndexed { index, arg ->
            statement.bindSqlValue(index = index + 1, value = arg)
        }
        statement.step()
    }
}

/**
 * Prepares [sql] once, then calls [block] for each item in [items].
 * Inside [block], bind parameters and call `step()` + `reset()`.
 * The statement is closed automatically after iteration completes.
 */
internal inline fun <T> SQLiteConnection.usePreparedBatch(
    sql: String,
    items: Iterable<T>,
    block: (SQLiteStatement, T) -> Unit,
) {
    prepare(sql).use { statement ->
        items.forEach { item ->
            block(statement, item)
            statement.reset()
        }
    }
}

private fun SQLiteStatement.bindSqlValue(
    index: Int,
    value: Any?,
) {
    when (value) {
        null -> bindNull(index)
        is ByteArray -> bindBlob(index, value)
        is Boolean -> bindBoolean(index, value)
        is Double -> bindDouble(index, value)
        is Float -> bindFloat(index, value)
        is Int -> bindInt(index, value)
        is Long -> bindLong(index, value)
        is Short -> bindLong(index, value.toLong())
        is String -> bindText(index, value)
        else -> bindText(index, value.toString())
    }
}
