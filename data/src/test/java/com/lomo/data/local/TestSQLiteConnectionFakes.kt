package com.lomo.data.local

import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

internal data class RecordedSqlStatement(
    val sql: String,
    val bindArgs: List<Any?>,
)

internal data class SqlMatch<T>(
    val predicate: (T) -> Boolean,
)

internal data class SQLiteQueryResult(
    val columnNames: List<String>,
    val rows: List<List<Any?>>,
) {
    companion object {
        val EMPTY = SQLiteQueryResult(columnNames = emptyList(), rows = emptyList())
    }
}

internal fun queryResult(
    vararg columnNames: String,
    rows: List<List<Any?>> = emptyList(),
): SQLiteQueryResult = SQLiteQueryResult(columnNames = columnNames.toList(), rows = rows)

internal fun rowOf(vararg values: Any?): List<Any?> = values.toList()

internal class RecordingSQLiteConnection(
    var queryHandler: (sql: String, bindArgs: List<Any?>) -> SQLiteQueryResult = { _, _ -> SQLiteQueryResult.EMPTY },
    var onExec: (sql: String, bindArgs: List<Any?>) -> Unit = { _, _ -> },
) : SQLiteConnection {
    val executedStatements = mutableListOf<RecordedSqlStatement>()

    override fun close() = Unit

    override fun inTransaction(): Boolean = false

    override fun prepare(sql: String): SQLiteStatement =
        RecordingSQLiteStatement(
            sql = sql,
            queryHandler = { statementSql, bindArgs -> queryHandler(statementSql, bindArgs) },
            onExec = { statementSql, bindArgs -> onExec(statementSql, bindArgs) },
            executedStatements = executedStatements,
        )

    fun execSQL(expectedSql: String) {
        assertExecution(
            exactly = currentVerifyExactly(),
            predicate = { statement -> statement.sql == expectedSql },
        )
    }

    fun execSQL(expectedSql: String, bindArgs: SqlMatch<Array<out Any?>>) {
        assertExecution(
            exactly = currentVerifyExactly(),
            predicate = { statement ->
                statement.sql == expectedSql && bindArgs.predicate(statement.bindArgs.toTypedArray())
            },
        )
    }

    fun execSQL(sqlMatcher: SqlMatch<String>) {
        assertExecution(
            exactly = currentVerifyExactly(),
            predicate = { statement -> sqlMatcher.predicate(statement.sql) },
        )
    }

    private fun assertExecution(
        exactly: Int?,
        predicate: (RecordedSqlStatement) -> Boolean,
    ) {
        val actual = executedStatements.count(predicate)
        if (exactly == null) {
            check(actual > 0) {
                "Expected at least one matching statement, but saw $actual in $executedStatements"
            }
            return
        }
        check(actual == exactly) {
            "Expected $exactly matching statement(s), but saw $actual in $executedStatements"
        }
    }
}

internal fun <T> match(predicate: (T) -> Boolean): SqlMatch<T> = SqlMatch(predicate)

internal fun verify(
    exactly: Int? = null,
    block: () -> Unit,
) {
    VERIFY_EXACTLY.set(exactly)
    try {
        block()
    } finally {
        VERIFY_EXACTLY.remove()
    }
}

internal fun RecordingSQLiteConnection.assertExecutedSql(predicate: (String) -> Boolean) {
    check(executedStatements.any { predicate(it.sql) }) {
        "Expected executed SQL matching predicate, but saw: ${executedStatements.map(RecordedSqlStatement::sql)}"
    }
}

internal fun RecordingSQLiteConnection.assertExecutedSql(
    expectedSql: String,
    count: Int = 1,
) {
    val actual = executedStatements.count { it.sql == expectedSql }
    check(actual == count) {
        "Expected '$expectedSql' to execute $count time(s), but saw $actual in ${executedStatements.map(RecordedSqlStatement::sql)}"
    }
}

internal fun RecordingSQLiteConnection.assertDidNotExecuteSql(predicate: (String) -> Boolean) {
    check(executedStatements.none { predicate(it.sql) }) {
        "Expected no executed SQL matching predicate, but saw: ${executedStatements.map(RecordedSqlStatement::sql)}"
    }
}

internal fun RecordingSQLiteConnection.assertExecutedSqlWithArgs(
    sql: String,
    bindArgs: List<Any?>,
) {
    check(executedStatements.any { it.sql == sql && it.bindArgs == bindArgs }) {
        "Expected '$sql' with bind args $bindArgs, but saw: $executedStatements"
    }
}

internal class JdbcSQLiteConnection(
    private val connection: Connection,
) : SQLiteConnection {
    override fun close() = Unit

    override fun inTransaction(): Boolean = !connection.autoCommit

    override fun prepare(sql: String): SQLiteStatement =
        JdbcSQLiteStatement(
            sql = sql,
            statement =
                if (isReadQuery(sql)) {
                    connection.prepareStatement(sql)
                } else {
                    connection.prepareStatement(sql)
                },
        )
}

internal fun Migration.migrateForTest(connection: SQLiteConnection) {
    runBlocking {
        migrate(connection)
    }
}

internal fun RoomDatabase.Callback.onOpenForTest(connection: SQLiteConnection) {
    runBlocking {
        onOpen(connection)
    }
}

private class RecordingSQLiteStatement(
    private val sql: String,
    private val queryHandler: (sql: String, bindArgs: List<Any?>) -> SQLiteQueryResult,
    private val onExec: (sql: String, bindArgs: List<Any?>) -> Unit,
    private val executedStatements: MutableList<RecordedSqlStatement>,
) : SQLiteStatement {
    private val bindings = linkedMapOf<Int, Any?>()
    private var queryResult: SQLiteQueryResult? = null
    private var rowIndex = -1

    override fun bindBlob(
        index: Int,
        value: ByteArray,
    ) {
        bindings[index] = value
    }

    override fun bindBoolean(
        index: Int,
        value: Boolean,
    ) {
        bindings[index] = value
    }

    override fun bindDouble(
        index: Int,
        value: Double,
    ) {
        bindings[index] = value
    }

    override fun bindFloat(
        index: Int,
        value: Float,
    ) {
        bindings[index] = value
    }

    override fun bindInt(
        index: Int,
        value: Int,
    ) {
        bindings[index] = value
    }

    override fun bindLong(
        index: Int,
        value: Long,
    ) {
        bindings[index] = value
    }

    override fun bindNull(index: Int) {
        bindings[index] = null
    }

    override fun bindText(
        index: Int,
        value: String,
    ) {
        bindings[index] = value
    }

    override fun clearBindings() {
        bindings.clear()
    }

    override fun close() = Unit

    override fun getBlob(index: Int): ByteArray = valueAt(index) as ByteArray

    override fun getBoolean(index: Int): Boolean =
        when (val value = valueAt(index)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString().toBoolean()
        }

    override fun getColumnCount(): Int = requireQueryResult().columnNames.size

    override fun getColumnName(index: Int): String = requireQueryResult().columnNames[index]

    override fun getColumnNames(): List<String> = requireQueryResult().columnNames

    override fun getColumnType(index: Int): Int = 0

    override fun getDouble(index: Int): Double =
        when (val value = valueAt(index)) {
            is Number -> value.toDouble()
            else -> value.toString().toDouble()
        }

    override fun getFloat(index: Int): Float =
        when (val value = valueAt(index)) {
            is Number -> value.toFloat()
            else -> value.toString().toFloat()
        }

    override fun getInt(index: Int): Int =
        when (val value = valueAt(index)) {
            is Number -> value.toInt()
            else -> value.toString().toInt()
        }

    override fun getLong(index: Int): Long =
        when (val value = valueAt(index)) {
            is Number -> value.toLong()
            else -> value.toString().toLong()
        }

    override fun getText(index: Int): String = valueAt(index).toString()

    override fun isNull(index: Int): Boolean = currentRow().getOrNull(index) == null

    override fun reset() {
        queryResult = null
        rowIndex = -1
    }

    override fun step(): Boolean {
        if (isReadQuery(sql)) {
            val result = requireQueryResult()
            val nextIndex = rowIndex + 1
            return if (nextIndex < result.rows.size) {
                rowIndex = nextIndex
                true
            } else {
                false
            }
        }
        val bindArgs = buildBindArgs()
        executedStatements += RecordedSqlStatement(sql = sql, bindArgs = bindArgs)
        onExec(sql, bindArgs)
        return false
    }

    private fun valueAt(index: Int): Any = requireNotNull(currentRow().getOrNull(index))

    private fun currentRow(): List<Any?> = requireQueryResult().rows[rowIndex]

    private fun requireQueryResult(): SQLiteQueryResult {
        if (queryResult == null) {
            queryResult = queryHandler(sql, buildBindArgs())
        }
        return requireNotNull(queryResult)
    }

    private fun buildBindArgs(): List<Any?> {
        val maxIndex = bindings.keys.maxOrNull() ?: return emptyList()
        return List(maxIndex) { index -> bindings[index + 1] }
    }
}

private class JdbcSQLiteStatement(
    private val sql: String,
    private val statement: PreparedStatement,
) : SQLiteStatement {
    private var resultSet: ResultSet? = null

    override fun bindBlob(
        index: Int,
        value: ByteArray,
    ) {
        statement.setBytes(index, value)
    }

    override fun bindBoolean(
        index: Int,
        value: Boolean,
    ) {
        statement.setBoolean(index, value)
    }

    override fun bindDouble(
        index: Int,
        value: Double,
    ) {
        statement.setDouble(index, value)
    }

    override fun bindFloat(
        index: Int,
        value: Float,
    ) {
        statement.setFloat(index, value)
    }

    override fun bindInt(
        index: Int,
        value: Int,
    ) {
        statement.setInt(index, value)
    }

    override fun bindLong(
        index: Int,
        value: Long,
    ) {
        statement.setLong(index, value)
    }

    override fun bindNull(index: Int) {
        statement.setObject(index, null)
    }

    override fun bindText(
        index: Int,
        value: String,
    ) {
        statement.setString(index, value)
    }

    override fun clearBindings() {
        statement.clearParameters()
    }

    override fun close() {
        resultSet?.close()
        statement.close()
    }

    override fun getBlob(index: Int): ByteArray = requireNotNull(resultSet).getBytes(index + 1)

    override fun getBoolean(index: Int): Boolean = requireNotNull(resultSet).getBoolean(index + 1)

    override fun getColumnCount(): Int = ensureResultSet().metaData.columnCount

    override fun getColumnName(index: Int): String = ensureResultSet().metaData.getColumnLabel(index + 1)

    override fun getColumnNames(): List<String> =
        List(getColumnCount()) { index ->
            getColumnName(index)
        }

    override fun getColumnType(index: Int): Int = ensureResultSet().metaData.getColumnType(index + 1)

    override fun getDouble(index: Int): Double = requireNotNull(resultSet).getDouble(index + 1)

    override fun getFloat(index: Int): Float = requireNotNull(resultSet).getFloat(index + 1)

    override fun getInt(index: Int): Int = requireNotNull(resultSet).getInt(index + 1)

    override fun getLong(index: Int): Long = requireNotNull(resultSet).getLong(index + 1)

    override fun getText(index: Int): String = requireNotNull(resultSet).getString(index + 1)

    override fun isNull(index: Int): Boolean {
        requireNotNull(resultSet).getObject(index + 1)
        return requireNotNull(resultSet).wasNull()
    }

    override fun reset() {
        resultSet?.close()
        resultSet = null
        statement.clearParameters()
    }

    override fun step(): Boolean {
        if (!isReadQuery(sql)) {
            statement.execute()
            return false
        }
        return ensureResultSet().next()
    }

    private fun ensureResultSet(): ResultSet {
        if (resultSet == null) {
            resultSet = statement.executeQuery()
        }
        return requireNotNull(resultSet)
    }
}

private fun isReadQuery(sql: String): Boolean {
    val normalized = sql.trimStart().uppercase()
    return normalized.startsWith("SELECT") || normalized.startsWith("PRAGMA")
}

private val VERIFY_EXACTLY = ThreadLocal<Int?>()

private fun currentVerifyExactly(): Int? = VERIFY_EXACTLY.get()
