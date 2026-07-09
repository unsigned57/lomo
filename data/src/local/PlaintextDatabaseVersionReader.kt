package com.lomo.data.local

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

internal data class PlaintextDatabaseInspection(
    val userVersion: Int,
    val quickCheckPassed: Boolean,
)

internal object PlaintextDatabaseVersionReader {
    private const val UNKNOWN_DB_VERSION = -1

    fun inspect(
        databaseFile: File,
        driver: SQLiteDriver = BundledSQLiteDriver(),
    ): PlaintextDatabaseInspection {
        val connection = driver.open(databaseFile.path)
        try {
            val userVersion = readSingleIntPragma(connection, "PRAGMA user_version")
            val quickCheckPassed = readSingleTextPragma(connection, "PRAGMA quick_check(1)") == "ok"
            return PlaintextDatabaseInspection(
                userVersion = userVersion,
                quickCheckPassed = quickCheckPassed,
            )
        } finally {
            connection.close()
        }
    }

    fun readUserVersion(
        databaseFile: File,
        driver: SQLiteDriver = BundledSQLiteDriver(),
    ): Int = inspect(databaseFile, driver).userVersion

    private fun readSingleIntPragma(
        connection: androidx.sqlite.SQLiteConnection,
        sql: String,
    ): Int {
        val statement = connection.prepare(sql)
        try {
            return if (statement.step()) statement.getLong(0).toInt() else UNKNOWN_DB_VERSION
        } finally {
            statement.close()
        }
    }

    private fun readSingleTextPragma(
        connection: androidx.sqlite.SQLiteConnection,
        sql: String,
    ): String? {
        val statement = connection.prepare(sql)
        try {
            return if (statement.step()) statement.getText(0).trim() else null
        } finally {
            statement.close()
        }
    }
}

internal fun File.hasPlaintextSqliteHeader(): Boolean {
    if (!exists() || length() < SQLITE_HEADER.size.toLong()) {
        return false
    }
    return inputStream().use { stream ->
        val header = ByteArray(SQLITE_HEADER.size)
        val bytesRead = stream.read(header)
        bytesRead == SQLITE_HEADER.size && header.contentEquals(SQLITE_HEADER)
    }
}

private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
