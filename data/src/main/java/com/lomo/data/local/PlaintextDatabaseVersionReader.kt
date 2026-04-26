package com.lomo.data.local

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

internal object PlaintextDatabaseVersionReader {
    private const val UNKNOWN_DB_VERSION = -1

    fun readUserVersion(
        databaseFile: File,
        driver: SQLiteDriver = BundledSQLiteDriver(),
    ): Int {
        val connection = driver.open(databaseFile.path)
        try {
            val statement = connection.prepare("PRAGMA user_version")
            try {
                return if (statement.step()) statement.getLong(0).toInt() else UNKNOWN_DB_VERSION
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
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
