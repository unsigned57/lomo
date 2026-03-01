package com.lomo.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
