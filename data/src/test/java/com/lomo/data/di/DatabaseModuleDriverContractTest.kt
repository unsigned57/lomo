package com.lomo.data.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: DatabaseModule (DataModule.kt, database open path)
 * - Behavior focus: MemoDatabase must be configured with the bundled SQLite driver so the
 *   app-controlled FTS5 table can run on-device, and the startup path must not force-open
 *   Room through the deprecated openHelper contract.
 * - Observable outcomes: Source-level presence of BundledSQLiteDriver + setDriver in DataModule
 *   and absence of openHelper.writableDatabase in the MemoDatabase provider.
 * - Red phase: Fails before the fix because DataModule currently omits BundledSQLiteDriver from
 *   the Room builder and still force-opens the database through db.openHelper.writableDatabase.
 * - Excludes: actual Android device SQLite execution, Room migration runtime behavior, and
 *   search result correctness after the database is opened.
 */
class DatabaseModuleDriverContractTest {
    private val dataModuleRoot = resolveModuleRoot("data")
    private val dataModuleSource = dataModuleRoot.resolve("src/main/java")
    private val dataModuleFile =
        dataModuleSource.resolve("com/lomo/data/di/DataModule.kt").also { file ->
            check(file.exists()) { "Could not find DataModule.kt in $dataModuleSource" }
        }

    @Test
    fun `database module configures MemoDatabase with bundled sqlite driver for fts5`() {
        val content = dataModuleFile.readText()

        assertTrue(
            "DataModule must import or reference BundledSQLiteDriver so MemoDatabase runs on bundled SQLite with FTS5 support.",
            content.contains("BundledSQLiteDriver"),
        )
        assertTrue(
            "DataModule must call setDriver(...) on the Room builder for MemoDatabase.",
            content.contains(".setDriver(") || content.contains("setDriver("),
        )
    }

    @Test
    fun `database module does not force open MemoDatabase through openHelper writableDatabase`() {
        val content = dataModuleFile.readText()

        assertFalse(
            "DataModule must not call db.openHelper.writableDatabase because the generated Room implementation no longer guarantees a SupportSQLiteOpenHelper-backed open path.",
            content.contains("openHelper.writableDatabase"),
        )
    }

    @Test
    fun `database module startup open path stays on native room3 apis`() {
        val content = dataModuleFile.readText()

        assertFalse(
            "DataModule must not use getSupportWrapper for startup open because the Room3 migration must stay on native SQLiteConnection APIs.",
            content.contains("getSupportWrapper"),
        )
        assertTrue(
            "DataModule should use Room3's native connection API to force-open the database during startup.",
            content.contains("useReaderConnection"),
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
