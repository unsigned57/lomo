package com.lomo.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: driver-backed Room transaction runners in the data layer.
 * - Behavior focus: hand-written transaction entrypoints for MemoDatabase must not use
 *   RoomDatabase.withTransaction once the database is configured with a SQLiteDriver, because
 *   that legacy extension can route through the deprecated SupportSQLiteOpenHelper contract.
 * - Observable outcomes: source-level absence of `database.withTransaction` in transaction runner
 *   sites and presence of the new writer-connection transaction helper.
 * - Red phase: Fails before the fix because transaction runners in DataModule, MemoMutationHandler,
 *   MemoVersionJournal, and S3SyncTransactionSupport still call `database.withTransaction`.
 * - Excludes: Android runtime execution, DAO SQL behavior, and memo search result correctness.
 */
class DriverBackedRoomTransactionContractTest {
    private val dataModuleRoot = resolveModuleRoot("data")
    private val dataModuleSource = dataModuleRoot.resolve("src/main/java")
    private val transactionCallSites =
        listOf(
            dataModuleSource.resolve("com/lomo/data/di/DataModule.kt"),
            dataModuleSource.resolve("com/lomo/data/repository/MemoMutationHandler.kt"),
            dataModuleSource.resolve("com/lomo/data/repository/MemoVersionJournal.kt"),
            dataModuleSource.resolve("com/lomo/data/repository/S3SyncTransactionSupport.kt"),
        )

    @Test
    fun `driver backed transaction call sites do not use RoomDatabase withTransaction`() {
        val offenders =
            transactionCallSites.filter { file ->
                file.readText().contains("database.withTransaction")
            }

        assertTrue(
            "Driver-backed transaction runners must not call database.withTransaction. Offenders: ${offenders.joinToString { it.path }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `data layer provides writer connection transaction helper for driver backed database`() {
        val helperFile =
            dataModuleSource.resolve("com/lomo/data/local/DriverBackedRoomTransaction.kt")
        assertTrue(
            "DriverBackedRoomTransaction helper must exist so hand-written transactions share the writer-connection path.",
            helperFile.exists(),
        )
        val content = helperFile.readText()
        assertTrue(
            "Driver-backed transaction helper must use Room's writer connection API.",
            content.contains("useWriterConnection"),
        )
        assertTrue(
            "Driver-backed transaction helper must start a writer transaction explicitly.",
            content.contains("immediateTransaction") || content.contains("withTransaction("),
        )
        assertFalse(
            "Driver-backed transaction helper must not fall back to database.withTransaction.",
            content.contains("withTransaction {"),
        )
    }

    @Test
    fun `memo refresh transaction helper suspends FTS triggers and rebuilds once per batch`() {
        val helperFile =
            dataModuleSource.resolve("com/lomo/data/local/DriverBackedRoomTransaction.kt")
        val content = helperFile.readText()

        assertTrue(
            "Memo refresh transaction helper must drop FTS triggers before batch writes.",
            content.contains("dropMemoFtsExternalContentTriggers"),
        )
        assertTrue(
            "Memo refresh transaction helper must recreate FTS triggers after batch writes.",
            content.contains("createMemoFtsExternalContentTriggers"),
        )
        assertTrue(
            "Memo refresh transaction helper must rebuild the external-content FTS index once after batch writes.",
            content.contains("rebuildMemoFtsExternalContentIndex"),
        )

        val dataModuleContent =
            dataModuleSource.resolve("com/lomo/data/di/DataModule.kt").readText()
        assertTrue(
            "MemoRefreshDbApplier must use the FTS-suspending driver-backed transaction helper.",
            dataModuleContent.contains("withDriverTransactionAndSuspendedMemoFtsTriggers"),
        )
    }

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val parent = currentDir.parentFile ?: currentDir
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
                parent.resolve(moduleName),
            )
        return checkNotNull(
            candidateRoots.firstOrNull { dir ->
                dir.name == moduleName && dir.resolve("build.gradle.kts").exists()
            },
        ) { "Failed to resolve $moduleName module root from $currentDirPath" }
    }
}
