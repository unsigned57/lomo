package com.lomo.data.local

import android.content.Context
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
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
            "workspace_mutation",
            "workspace_head",
            "workspace_snapshot_entry",
            "workspace_snapshot",
            "snapshot_blob",
        )

    fun prepareBeforeOpen(
        context: Context,
        targetVersion: Int,
        databaseName: String = DATABASE_NAME,
        migrationEdges: List<Pair<Int, Int>>,
        inspectDatabase: (File) -> PlaintextDatabaseInspection = PlaintextDatabaseVersionReader::inspect,
    ) {
        val databaseFile = context.getDatabasePath(databaseName)
        if (!databaseFile.exists()) return
        if (!databaseFile.hasPlaintextSqliteHeader()) {
            Timber.tag(TAG).w("Resetting db '%s' before Room open because the SQLite header is invalid", databaseName)
            deleteDatabaseArtifacts(context, databaseName)
            return
        }

        val inspection =
            runCatching {
                inspectDatabase(databaseFile)
            }.getOrElse { throwable ->
                Timber.tag(TAG).w(throwable, "Failed to inspect db file before Room open: %s", databaseFile.path)
                PlaintextDatabaseInspection(
                    userVersion = UNKNOWN_DB_VERSION,
                    quickCheckPassed = false,
                )
            }

        if (!inspection.quickCheckPassed) {
            Timber.tag(TAG).w(
                "Resetting db '%s' before Room open because quick_check did not return ok (existing=%d)",
                databaseName,
                inspection.userVersion,
            )
            deleteDatabaseArtifacts(context, databaseName)
            return
        }

        if (shouldResetDatabase(inspection.userVersion, targetVersion, migrationEdges)) {
            Timber.tag(TAG).w(
                "Resetting db '%s' before Room open (existing=%d, target=%d)",
                databaseName,
                inspection.userVersion,
                targetVersion,
            )
            deleteDatabaseArtifacts(context, databaseName)
        }
    }

    fun cleanupLegacyArtifactsCallback(): RoomDatabase.Callback =
        object : RoomDatabase.Callback() {
            override suspend fun onOpen(connection: SQLiteConnection) {
                val db = connection
                legacyTableNames.forEach { legacyTable ->
                    db.execSQL("DROP TABLE IF EXISTS `$legacyTable`")
                }
                ensureMemoFtsTable(db)
            }
        }

    internal fun shouldResetDatabase(
        existingVersion: Int,
        targetVersion: Int,
        migrationEdges: List<Pair<Int, Int>>,
    ): Boolean =
        when {
            existingVersion <= 0 -> true
            existingVersion > targetVersion -> true
            else -> !canReachTargetVersion(existingVersion, targetVersion, migrationEdges)
        }

    internal fun canReachTargetVersion(
        fromVersion: Int,
        targetVersion: Int,
        migrationEdges: List<Pair<Int, Int>>,
    ): Boolean =
        when {
            fromVersion == targetVersion -> true
            fromVersion <= 0 || targetVersion <= 0 || fromVersion > targetVersion -> false
            else -> canReachTargetVersionByGraph(fromVersion, targetVersion, migrationEdges)
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

    private fun canReachTargetVersionByGraph(
        fromVersion: Int,
        targetVersion: Int,
        migrationEdges: List<Pair<Int, Int>>,
    ): Boolean {
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
            if (nextVersions.any { it == targetVersion }) {
                return true
            }
            nextVersions
                .filter { it <= targetVersion && visited.add(it) }
                .forEach(queue::addLast)
        }

        return false
    }
}
