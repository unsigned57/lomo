package com.lomo.data.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: DatabaseModule (DataModule.kt, DatabaseModule object)
 * - Behavior focus: Plaintext-only database creation contract – the database must be built with
 *   standard Room + SQLite and must NOT include SQLCipher, cipher-key setup, or any database-unlock
 *   gating mechanism. The app-lock (biometric) gate lives in the UI layer and is intentionally
 *   separate from the database layer.
 * - Observable outcomes: Source-level absence of cipher-related symbols in the database module
 *   and the data DI module; absence of DatabaseStartupRequirement or database-unlock gate types.
 * - Red phase: Not applicable – test-only coverage lock; no production change required because
 *   the codebase already uses plaintext Room. These tests will fail if cipher code is ever added.
 * - Excludes: Runtime database opening, Room migration correctness, and biometric app-lock logic.
 */
class DatabaseModuleContractTest {
    private val dataModuleRoot = resolveModuleRoot("data")
    private val dataModuleSource = dataModuleRoot.resolve("src/main/java")

    @Test
    fun `database module does not import sqlcipher`() {
        val offenders =
            kotlinSourceFiles(dataModuleSource).filter { file ->
                file.readText().lineSequence().any { line ->
                    CIPHER_IMPORT_PATTERNS.any { pattern -> pattern.containsMatchIn(line) }
                }
            }

        assertTrue(
            "DataModule must not reference SQLCipher. Offenders: ${offenders.joinToString { it.path }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `database module does not reference cipher key or passphrase setup`() {
        val cipherKeyPatterns =
            listOf(
                Regex("""setKey\s*\("""),
                Regex("""supportFactory\s*\("""),
                Regex("""SQLiteDatabase\.loadLibs"""),
                Regex("""passphrase""", RegexOption.IGNORE_CASE),
            )

        val offenders =
            kotlinSourceFiles(dataModuleSource).filter { file ->
                val text = file.readText()
                cipherKeyPatterns.any { pattern -> pattern.containsMatchIn(text) }
            }

        assertTrue(
            "DataModule must not set cipher keys or passphrases. Offenders: ${offenders.joinToString { it.path }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `database module does not reference DatabaseStartupRequirement type`() {
        val allKotlinFiles =
            listOf(
                dataModuleSource,
                resolveModuleRoot("app").resolve("src/main/java"),
                resolveModuleRoot("domain").resolve("src/main/java"),
            ).flatMap(::kotlinSourceFiles)

        val offenders =
            allKotlinFiles.filter { file ->
                file.readText().contains("DatabaseStartupRequirement")
            }

        assertTrue(
            "No source file may reference DatabaseStartupRequirement. Offenders: ${offenders.joinToString { it.path }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `database module does not reference database unlock repository or state`() {
        val unlockPatterns =
            listOf(
                Regex("""DatabaseAccessRepository"""),
                Regex("""DatabaseUnlock"""),
                Regex("""databaseUnlock"""),
                Regex("""unlockDatabase"""),
            )

        val allKotlinFiles =
            listOf(
                dataModuleSource,
                resolveModuleRoot("app").resolve("src/main/java"),
            ).flatMap(::kotlinSourceFiles)

        val offenders =
            allKotlinFiles.filter { file ->
                val text = file.readText()
                unlockPatterns.any { pattern -> pattern.containsMatchIn(text) }
            }

        assertTrue(
            "Database unlock types must not exist in app or data. Offenders: ${offenders.joinToString { it.path }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `DatabaseModule builds MemoDatabase using plain Room databaseBuilder without cipher factory`() {
        val diSourceFile =
            listOf(
                dataModuleSource.resolve("com/lomo/data/di/DataModule.kt"),
            ).firstOrNull { it.exists() }

        checkNotNull(diSourceFile) { "Could not find DataModule.kt in $dataModuleSource" }

        val content = diSourceFile.readText()

        // Must use Room.databaseBuilder (may be split across lines)
        val containsRoomBuilder =
            content.contains("Room.databaseBuilder") ||
                (content.contains("Room") && content.contains(".databaseBuilder"))
        assertTrue(
            "DataModule must use Room.databaseBuilder to create MemoDatabase",
            containsRoomBuilder,
        )
        // Must NOT use any cipher factory
        assertFalse(
            "DataModule must not pass a cipher SupportFactory to Room builder",
            content.contains("SupportFactory") || content.contains("supportFactory"),
        )
    }

    @Test
    fun `MemoDatabase keeps lomo_fts out of Room managed schema and MemoFtsEntity is not Fts4`() {
        val memoFtsEntityFile =
            dataModuleSource.resolve("com/lomo/data/local/entity/MemoFtsEntity.kt")
        check(memoFtsEntityFile.exists()) { "Could not find MemoFtsEntity.kt in $dataModuleSource" }
        val memoFtsEntityContent = memoFtsEntityFile.readText()
        val memoDatabaseFile =
            dataModuleSource.resolve("com/lomo/data/local/MemoDatabase.kt")
        check(memoDatabaseFile.exists()) { "Could not find MemoDatabase.kt in $dataModuleSource" }
        val memoDatabaseContent = memoDatabaseFile.readText()

        assertTrue(
            "Fresh-install FTS5 must be maintained outside Room's managed schema so manual FTS5 DDL can be used.",
            !memoDatabaseContent.contains("MemoFtsEntity::class"),
        )
        assertFalse(
            "MemoFtsEntity must not keep the legacy FTS4 annotation now that lomo_fts is maintained manually.",
            memoFtsEntityContent.contains("@Fts4"),
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

    private fun kotlinSourceFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private companion object {
        val CIPHER_IMPORT_PATTERNS =
            listOf(
                Regex("""import net\.zetetic\.database"""),
                Regex("""import net\.sqlcipher"""),
                Regex("""import com\.commonsware.*cipher""", RegexOption.IGNORE_CASE),
                Regex("""sqlcipher""", RegexOption.IGNORE_CASE),
                Regex("""SQLCipherUtils"""),
            )
    }
}
