/*
 * Behavior Contract:
 * - Unit under test: DatabaseTransitionStrategy
 * - Behavior focus: atomic file-based database transitions and backup/recovery.
 * - Observable outcomes: file system state after transition, recovery from partial failures.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: SQLite execution, Room database internals.
 */
package com.lomo.data.local

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Behavior Contract:
 * - Unit under test: DatabaseTransitionStrategy
 * - Behavior focus: reset gating based on migration-graph reachability, legacy-table cleanup, and pre-open
 *   no-op behavior.
 * - Observable outcomes: Boolean reset decisions, executed DROP TABLE statements, and deleteDatabase
 *   side-effect suppression.
 * - TDD proof: Fails before the fix because the strategy still treats every forward upgrade version as safe
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
class DatabaseTransitionStrategyTest : DataFunSpec() {
    init {
        test("shouldResetDatabase_returnsTrue_forUnknownVersion") { shouldResetDatabase_returnsTrue_forUnknownVersion() }

        test("shouldResetDatabase_returnsFalse_forSameVersion") { shouldResetDatabase_returnsFalse_forSameVersion() }

        test("shouldResetDatabase_returnsFalse_forSupportedStableBaselineVersion") { shouldResetDatabase_returnsFalse_forSupportedStableBaselineVersion() }

        test("shouldResetDatabase_returnsFalse_forReachableInternalVersionOutsideStableWindow") { shouldResetDatabase_returnsFalse_forReachableInternalVersionOutsideStableWindow() }

        test("shouldResetDatabase_returnsTrue_forLegacyVersionWithoutMigrationPath") { shouldResetDatabase_returnsTrue_forLegacyVersionWithoutMigrationPath() }

        test("shouldResetDatabase_returnsTrue_forDowngrade") { shouldResetDatabase_returnsTrue_forDowngrade() }

        test("shouldResetDatabase_returnsTrue_forZeroVersion") { shouldResetDatabase_returnsTrue_forZeroVersion() }

        test("canReachTargetVersion_returnsTrue_forTransitivePath") { canReachTargetVersion_returnsTrue_forTransitivePath() }

        test("canReachTargetVersion_returnsFalse_forDowngradeDirection") { canReachTargetVersion_returnsFalse_forDowngradeDirection() }

        test("canReachTargetVersion_returnsTrue_whenAlreadyAtTarget") { canReachTargetVersion_returnsTrue_whenAlreadyAtTarget() }

        test("canReachTargetVersion_returnsFalse_forInvalidNonPositiveVersions") { canReachTargetVersion_returnsFalse_forInvalidNonPositiveVersions() }

        test("cleanupLegacyArtifactsCallback_dropsEveryLegacyTableOnOpen") { cleanupLegacyArtifactsCallback_dropsEveryLegacyTableOnOpen() }

        test("prepareBeforeOpen_returnsEarly_whenDatabaseFileDoesNotExist") { prepareBeforeOpen_returnsEarly_whenDatabaseFileDoesNotExist() }

        test("canReachTargetVersion_returnsFalse_whenBackwardEdgesCannotAdvanceGraph") { canReachTargetVersion_returnsFalse_whenBackwardEdgesCannotAdvanceGraph() }

        test("canReachTargetVersion_returnsFalse_whenCycleNeverReachesTarget") { canReachTargetVersion_returnsFalse_whenCycleNeverReachesTarget() }
    }


    private val migrationEdges = ALL_DATABASE_MIGRATIONS.map { it.startVersion to it.endVersion }

    private fun shouldResetDatabase_returnsTrue_forUnknownVersion() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = -1,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        (result).shouldBeTrue()
    }

    private fun shouldResetDatabase_returnsFalse_forSameVersion() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = MEMO_DATABASE_VERSION,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        (result).shouldBeFalse()
    }

    private fun shouldResetDatabase_returnsFalse_forSupportedStableBaselineVersion() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = 44,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        (result).shouldBeFalse()
    }

    private fun shouldResetDatabase_returnsFalse_forReachableInternalVersionOutsideStableWindow() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = 34,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        (result).shouldBeFalse()
    }

    private fun shouldResetDatabase_returnsTrue_forLegacyVersionWithoutMigrationPath() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = 31,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        (result).shouldBeTrue()
    }

    private fun shouldResetDatabase_returnsTrue_forDowngrade() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = MEMO_DATABASE_VERSION + 1,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        (result).shouldBeTrue()
    }

    private fun shouldResetDatabase_returnsTrue_forZeroVersion() {
        val result =
            DatabaseTransitionStrategy.shouldResetDatabase(
                existingVersion = 0,
                targetVersion = MEMO_DATABASE_VERSION,
                migrationEdges = migrationEdges,
            )

        (result).shouldBeTrue()
    }

    private fun canReachTargetVersion_returnsTrue_forTransitivePath() {
        val migrationEdges = listOf(18 to 19, 19 to 20)
        val result =
            DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 18,
                targetVersion = 20,
                migrationEdges = migrationEdges,
            )

        (result).shouldBeTrue()
    }

    private fun canReachTargetVersion_returnsFalse_forDowngradeDirection() {
        val migrationEdges = listOf(18 to 19, 19 to 20)
        val result =
            DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 20,
                targetVersion = 19,
                migrationEdges = migrationEdges,
            )

        (result).shouldBeFalse()
    }

    private fun canReachTargetVersion_returnsTrue_whenAlreadyAtTarget() {
        val result =
            DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 20,
                targetVersion = 20,
                migrationEdges = emptyList(),
            )

        (result).shouldBeTrue()
    }

    private fun canReachTargetVersion_returnsFalse_forInvalidNonPositiveVersions() {
        val migrationEdges = listOf(18 to 19, 19 to 20)

        (DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 0,
                targetVersion = 20,
                migrationEdges = migrationEdges,
            )).shouldBeFalse()
        (DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 18,
                targetVersion = 0,
                migrationEdges = migrationEdges,
            )).shouldBeFalse()
    }

    private fun cleanupLegacyArtifactsCallback_dropsEveryLegacyTableOnOpen() {
        val database = RecordingSQLiteConnection()

        DatabaseTransitionStrategy.cleanupLegacyArtifactsCallback().onOpenForTest(database)

        database.executedStatements.count { it.sql == "DROP TABLE IF EXISTS `memos`" } shouldBe 1
        database.executedStatements.count { it.sql == "DROP TABLE IF EXISTS `image_cache`" } shouldBe 1
        database.executedStatements.count { it.sql == "DROP TABLE IF EXISTS `tags`" } shouldBe 1
        database.executedStatements.count { it.sql == "DROP TABLE IF EXISTS `memo_tag_cross_ref`" } shouldBe 1
        database.executedStatements.count { it.sql == "DROP TABLE IF EXISTS `memos_fts`" } shouldBe 1
        database.executedStatements.count { it.sql == "DROP TABLE IF EXISTS `file_sync_metadata`" } shouldBe 1
    }

    private fun prepareBeforeOpen_returnsEarly_whenDatabaseFileDoesNotExist() {
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

    private fun canReachTargetVersion_returnsFalse_whenBackwardEdgesCannotAdvanceGraph() {
        val migrationEdges = listOf(18 to 17, 17 to 20)

        val result =
            DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 18,
                targetVersion = 20,
                migrationEdges = migrationEdges,
            )

        (result).shouldBeFalse()
    }

    private fun canReachTargetVersion_returnsFalse_whenCycleNeverReachesTarget() {
        val migrationEdges = listOf(18 to 19, 19 to 18)

        val result =
            DatabaseTransitionStrategy.canReachTargetVersion(
                fromVersion = 18,
                targetVersion = 20,
                migrationEdges = migrationEdges,
            )

        (result).shouldBeFalse()
    }
}
