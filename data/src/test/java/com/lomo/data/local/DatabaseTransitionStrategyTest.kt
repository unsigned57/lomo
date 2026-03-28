package com.lomo.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: DatabaseTransitionStrategy
 * - Behavior focus: reset gating, migration-graph reachability, destructive fallback ranges, legacy-table cleanup, and pre-open no-op behavior.
 * - Observable outcomes: Boolean reset decisions, destructive-version arrays, executed DROP TABLE statements, and deleteDatabase side-effect suppression.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: Android SQLite implementation details, Timber logging, and Room migration SQL bodies themselves.
 */
class DatabaseTransitionStrategyTest {
    @Test
    fun shouldResetDatabase_returnsTrue_forUnknownVersion() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = -1,
                targetVersion = 24,
            )

        assertTrue(result)
    }

    @Test
    fun shouldResetDatabase_returnsFalse_forSameVersion() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = 24,
                targetVersion = 24,
            )

        assertFalse(result)
    }

    @Test
    fun shouldResetDatabase_returnsFalse_forAnyUpgradeVersion() {
        for (version in 1..23) {
            val result =
                DatabaseTransitionStrategy.shouldResetDatabase(
                    existingVersion = version,
                    targetVersion = 24,
                )

            assertFalse("Version $version should not trigger reset", result)
        }
    }

    @Test
    fun shouldResetDatabase_returnsTrue_forDowngrade() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = 25,
                targetVersion = 24,
            )

        assertTrue(result)
    }

    @Test
    fun shouldResetDatabase_returnsTrue_forZeroVersion() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = 0,
                targetVersion = 24,
            )

        assertTrue(result)
    }

    @Test
    fun canReachTargetVersion_returnsTrue_forTransitivePath() {
        val migrationEdges = listOf(18 to 19, 19 to 20)
        val result =
            DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 18,
                targetVersion = 20,
                migrationEdges = migrationEdges,
            )

        assertTrue(result)
    }

    @Test
    fun canReachTargetVersion_returnsFalse_forDowngradeDirection() {
        val migrationEdges = listOf(18 to 19, 19 to 20)
        val result =
            DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 20,
                targetVersion = 19,
                migrationEdges = migrationEdges,
            )

        assertFalse(result)
    }

    @Test
    fun canReachTargetVersion_returnsTrue_whenAlreadyAtTarget() {
        val result =
            DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 20,
                targetVersion = 20,
                migrationEdges = emptyList(),
            )

        assertTrue(result)
    }

    @Test
    fun canReachTargetVersion_returnsFalse_forInvalidNonPositiveVersions() {
        val migrationEdges = listOf(18 to 19, 19 to 20)

        assertFalse(
            DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 0,
                targetVersion = 20,
                migrationEdges = migrationEdges,
            ),
        )
        assertFalse(
            DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 18,
                targetVersion = 0,
                migrationEdges = migrationEdges,
            ),
        )
    }

    @Test
    fun fallbackToDestructiveFromVersions_isEmpty_whenConsolidationMigrationsExist() {
        val result =
            DatabaseTransitionStrategy.fallbackToDestructiveFromVersions(
                migrations = ALL_DATABASE_MIGRATIONS.toList(),
                targetVersion = MEMO_DATABASE_VERSION,
            )

        assertArrayEquals(intArrayOf(), result)
    }

    @Test
    fun fallbackToDestructiveFromVersions_generatesLegacyRange_forPartialMigrations() {
        val partialMigrations =
            listOf(
                object : Migration(18, 19) {
                    override fun migrate(db: SupportSQLiteDatabase) = Unit
                },
                object : Migration(19, 20) {
                    override fun migrate(db: SupportSQLiteDatabase) = Unit
                },
            )

        val result =
            DatabaseTransitionStrategy.fallbackToDestructiveFromVersions(
                migrations = partialMigrations,
                targetVersion = 20,
            )

        assertArrayEquals((1..17).toList().toIntArray(), result)
    }

    @Test
    fun fallbackToDestructiveFromVersions_returnsFullLegacyRange_whenNoMigrationsExist() {
        val result =
            DatabaseTransitionStrategy.fallbackToDestructiveFromVersions(
                migrations = emptyList(),
                targetVersion = 4,
            )

        assertArrayEquals(intArrayOf(1, 2, 3), result)
    }

    @Test
    fun cleanupLegacyArtifactsCallback_dropsEveryLegacyTableOnOpen() {
        val database = mockk<SupportSQLiteDatabase>(relaxed = true)

        DatabaseTransitionStrategy.cleanupLegacyArtifactsCallback().onOpen(database)

        verify(exactly = 1) { database.execSQL("DROP TABLE IF EXISTS `memos`") }
        verify(exactly = 1) { database.execSQL("DROP TABLE IF EXISTS `image_cache`") }
        verify(exactly = 1) { database.execSQL("DROP TABLE IF EXISTS `tags`") }
        verify(exactly = 1) { database.execSQL("DROP TABLE IF EXISTS `memo_tag_cross_ref`") }
        verify(exactly = 1) { database.execSQL("DROP TABLE IF EXISTS `memos_fts`") }
        verify(exactly = 1) { database.execSQL("DROP TABLE IF EXISTS `file_sync_metadata`") }
    }

    @Test
    fun prepareBeforeOpen_returnsEarly_whenDatabaseFileDoesNotExist() {
        val context = mockk<android.content.Context>(relaxed = true)
        val missingFile = java.io.File.createTempFile("db-transition-missing", ".db").apply { delete() }
        every { context.getDatabasePath("missing.db") } returns missingFile

        DatabaseTransitionStrategy.prepareBeforeOpen(
            context = context,
            targetVersion = 24,
            databaseName = "missing.db",
        )

        verify(exactly = 0) { context.deleteDatabase(any()) }
    }

    @Test
    fun canReachTargetVersion_returnsFalse_whenBackwardEdgesCannotAdvanceGraph() {
        val migrationEdges = listOf(18 to 17, 17 to 20)

        val result =
            DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 18,
                targetVersion = 20,
                migrationEdges = migrationEdges,
            )

        assertFalse(result)
    }

    @Test
    fun canReachTargetVersion_returnsFalse_whenCycleNeverReachesTarget() {
        val migrationEdges = listOf(18 to 19, 19 to 18)

        val result =
            DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 18,
                targetVersion = 20,
                migrationEdges = migrationEdges,
            )

        assertFalse(result)
    }
}
