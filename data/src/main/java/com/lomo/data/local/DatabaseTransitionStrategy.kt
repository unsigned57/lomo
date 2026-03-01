package com.lomo.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import timber.log.Timber
import java.io.File

/**
 * Handles database upgrade transitions to avoid stale schema artifacts from older app versions.
 */
internal object DatabaseTransitionStrategy {
    private const val TAG = "DbTransitionStrategy"
    internal const val DATABASE_NAME = "lomo.db"
    private const val UNKNOWN_DB_VERSION = -1
    private val legacyTableNames =
        listOf(
            "memos",
            "image_cache",
            "tags",
            "memo_tag_cross_ref",
            "memos_fts",
            "file_sync_metadata",
        )

    fun fallbackToDestructiveFromVersions(
        migrations: List<Migration>,
        targetVersion: Int,
    ): IntArray {
        val minMigratedStart =
            migrations
                .asSequence()
                .map { it.startVersion }
                .filter { it in 1..targetVersion }
                .minOrNull() ?: targetVersion
        return if (minMigratedStart <= 1) {
            intArrayOf()
        } else {
            (1 until minMigratedStart).toList().toIntArray()
        }
    }

    fun prepareBeforeOpen(
        context: Context,
        targetVersion: Int,
        migrations: List<Migration>,
        databaseName: String = DATABASE_NAME,
    ) {
        val databaseFile = context.getDatabasePath(databaseName)
        if (!databaseFile.exists()) return

        val existingVersion = readUserVersion(databaseFile)

        // Only reset for truly invalid states: corrupt version or downgrade.
        // All upgrade paths (1..target) are now covered by consolidation +
        // incremental migrations, so path-checking is no longer needed.
        if (shouldResetDatabase(existingVersion, targetVersion)) {
            Timber.tag(TAG).w(
                "Resetting db '%s' before Room open (existing=%d, target=%d)",
                databaseName,
                existingVersion,
                targetVersion,
            )
            deleteDatabaseArtifacts(context, databaseName)
        }
    }

    fun cleanupLegacyArtifactsCallback(): RoomDatabase.Callback =
        object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                legacyTableNames.forEach { legacyTable ->
                    db.execSQL("DROP TABLE IF EXISTS `$legacyTable`")
                }
            }
        }

    internal fun shouldResetDatabase(
        existingVersion: Int,
        targetVersion: Int,
    ): Boolean {
        if (existingVersion <= 0) return true
        if (existingVersion == targetVersion) return false
        if (existingVersion > targetVersion) return true
        // All upgrade paths (1..target) are covered by migrations.
        return false
    }

    internal fun canReachTargetVersion(
        fromVersion: Int,
        targetVersion: Int,
        migrationEdges: List<Pair<Int, Int>>,
    ): Boolean {
        if (fromVersion == targetVersion) return true
        if (fromVersion <= 0 || targetVersion <= 0 || fromVersion > targetVersion) return false

        val graph = mutableMapOf<Int, MutableList<Int>>()
        migrationEdges.forEach { (from, to) ->
            if (to > from) {
                graph.getOrPut(from) { mutableListOf() }.add(to)
            }
        }

        val queue = ArrayDeque<Int>()
        val visited = mutableSetOf<Int>()
        queue.addLast(fromVersion)
        visited.add(fromVersion)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val nextVersions = graph[current].orEmpty()
            for (next in nextVersions) {
                if (next == targetVersion) return true
                if (next <= targetVersion && visited.add(next)) {
                    queue.addLast(next)
                }
            }
        }

        return false
    }

    private fun readUserVersion(databaseFile: File): Int =
        runCatching {
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sqliteDb ->
                sqliteDb.version
            }
        }.getOrElse { throwable ->
            Timber.tag(TAG).w(throwable, "Failed to read user_version from %s", databaseFile.path)
            UNKNOWN_DB_VERSION
        }

    private fun deleteDatabaseArtifacts(
        context: Context,
        databaseName: String,
    ) {
        val databaseFile = context.getDatabasePath(databaseName)
        runCatching {
            context.deleteDatabase(databaseName)
        }.onFailure { throwable ->
            Timber.tag(TAG).w(throwable, "context.deleteDatabase failed for %s", databaseName)
        }

        deleteIfExists(databaseFile)
        deleteIfExists(File("${databaseFile.path}-wal"))
        deleteIfExists(File("${databaseFile.path}-shm"))
        deleteIfExists(File("${databaseFile.path}-journal"))
    }

    private fun deleteIfExists(file: File) {
        if (!file.exists()) return
        if (!file.delete()) {
            Timber.tag(TAG).w("Failed to delete stale db artifact: %s", file.path)
        }
    }
}
