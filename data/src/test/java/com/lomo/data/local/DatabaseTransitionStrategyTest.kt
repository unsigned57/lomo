/*
 * Test Contract:
 * - Unit under test: DatabaseTransitionStrategy
 * - Behavior focus: atomic file-based database transitions and backup/recovery.
 * - Observable outcomes: file system state after transition, recovery from partial failures.
 * - Red phase: Not applicable - unit tests for database transition safety.
 * - Excludes: SQLite execution, Room database internals.
 */
package com.lomo.data.local

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: DatabaseTransitionStrategy
 * - Behavior focus: reset gating based on migration-graph reachability, legacy-table cleanup, and pre-open
 *   no-op behavior.
 * - Observable outcomes: Boolean reset decisions, executed DROP TABLE statements, and deleteDatabase
 *   side-effect suppression.
 * - Red phase: Fails before the fix because the strategy still treats every forward upgrade version as safe
 *   even when no migration path to the current schema exists.
 * - Excludes: Android SQLite implementation details, Timber logging, and Room migration SQL bodies themselves.
 */
/*
 * Test Change Justification (release-window reset policy):
 * - Reason category: migration support contract changed.
 * - Old behavior/assertion being replaced: any forward-only version below the target avoided pre-open reset,
 *   and fallback range helpers assumed a universal migration era.
 * - Why old assertion is no longer correct: after pruning universal direct migrations, unsupported legacy
 *   versions must be reset before Room open while reachable internal versions still upgrade safely.
 * - Coverage preserved by: path-reachability assertions for supported stable and internal versions, plus
 *   explicit reset assertions for versions without a route to the current schema.
 * - Why this is not fitting the test to the implementation: the migration graph itself is now the product
 *   contract for preserve-vs-reset behavior.
 */
class DatabaseTransitionStrategyTest {
    private val migrationEdges = ALL_DATABASE_MIGRATIONS.map { it.startVersion to it.endVersion }

    @Test
    fun shouldResetDatabase_returnsTrue_forUnknownVersion() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = -1,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        assertTrue(result)
    }

    @Test
    fun shouldResetDatabase_returnsFalse_forSameVersion() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = MEMO_DATABASE_VERSION,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        assertFalse(result)
    }

    @Test
    fun shouldResetDatabase_returnsFalse_forSupportedStableBaselineVersion() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = 44,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        assertFalse(result)
    }

    @Test
    fun shouldResetDatabase_returnsFalse_forReachableInternalVersionOutsideStableWindow() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = 34,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        assertFalse(result)
    }

    @Test
    fun shouldResetDatabase_returnsTrue_forLegacyVersionWithoutMigrationPath() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = 31,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        assertTrue(result)
    }

    @Test
    fun shouldResetDatabase_returnsTrue_forDowngrade() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = MEMO_DATABASE_VERSION + 1,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        assertTrue(result)
    }

    @Test
    fun shouldResetDatabase_returnsTrue_forZeroVersion() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = 0,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
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
    fun cleanupLegacyArtifactsCallback_dropsEveryLegacyTableOnOpen() {
        val database = RecordingSQLiteConnection()

        DatabaseTransitionStrategy.cleanupLegacyArtifactsCallback().onOpenForTest(database)

        assertEquals(1, database.executedStatements.count { it.sql == "DROP TABLE IF EXISTS `memos`" })
        assertEquals(1, database.executedStatements.count { it.sql == "DROP TABLE IF EXISTS `image_cache`" })
        assertEquals(1, database.executedStatements.count { it.sql == "DROP TABLE IF EXISTS `tags`" })
        assertEquals(1, database.executedStatements.count { it.sql == "DROP TABLE IF EXISTS `memo_tag_cross_ref`" })
        assertEquals(1, database.executedStatements.count { it.sql == "DROP TABLE IF EXISTS `memos_fts`" })
        assertEquals(1, database.executedStatements.count { it.sql == "DROP TABLE IF EXISTS `file_sync_metadata`" })
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
            migrationEdges = migrationEdges,
        )

        verify(exactly = 0) { context.deleteDatabase("missing.db") }
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
