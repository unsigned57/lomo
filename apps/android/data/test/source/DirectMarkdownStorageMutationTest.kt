package com.lomo.data.source

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



import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.KotestTemporaryFolder
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Behavior Contract:
 * - Unit under test: direct markdown delete helpers (directDeleteFile, directDeleteTrashFile).
 * - Behavior focus: permanent delete on direct-storage Markdown files always invokes the
 *   secure wipe pass before unlinking — the previous "skip wipe when the user opted out"
 *   path is gone. Hard delete is irreversible, and the in-app trash already provides the
 *   non-destructive recovery surface, so wiping is now a non-configurable application
 *   invariant rather than a settings toggle.
 * - Observable outcomes: target file removal plus injected secure-wipe callback invocation
 *   for both the main directory and the trash directory.
 * - TDD proof: Fails before the internalization because the helpers still accept an
 *   `overwriteBeforeUnlink` parameter and can skip the wipe when it is `false`.
 * - Excludes: SAF-backed deletion, low-level overwrite byte patterns, and the legacy
 *   "skip wipe" path.
 */
class DirectMarkdownStorageMutationTest : DataFunSpec() {
    private lateinit var tempFolder: KotestTemporaryFolder

    init {
        beforeTest {
            tempFolder = KotestTemporaryFolder()
        }

        afterTest {
            tempFolder.cleanup()
        }

        test("directDeleteFile always invokes secure wipe before unlinking the file") {
            runTest {
                val root = tempFolder.newFolder("lomo-direct-delete")
                val target = root.resolve("2026_04_20.md").apply { writeText("secret") }
                var secureWipeInvoked = false

                directDeleteFile(
                    rootDir = root,
                    filename = target.name,
                    secureWipe = { file ->
                        secureWipeInvoked = true
                        file.exists().shouldBeTrue()
                    },
                )

                secureWipeInvoked.shouldBeTrue()
                target.exists().shouldBeFalse()
            }
        }

        test("directDeleteTrashFile always invokes secure wipe before unlinking the trash entry") {
            runTest {
                val root = tempFolder.newFolder("lomo-direct-trash-delete")
                val trashDir = root.resolve(".trash").apply { mkdirs() }
                val target = trashDir.resolve("2026_04_20.md").apply { writeText("secret") }
                var secureWipeInvoked = false

                directDeleteTrashFile(
                    rootDir = root,
                    filename = target.name,
                    secureWipe = { file ->
                        secureWipeInvoked = true
                        file.exists().shouldBeTrue()
                    },
                )

                secureWipeInvoked.shouldBeTrue()
                target.exists().shouldBeFalse()
            }
        }

        test("delete helpers no-op the wipe when the file does not exist") {
            runTest {
                val root = tempFolder.newFolder("lomo-direct-delete-missing")
                var secureWipeInvoked = false

                directDeleteFile(
                    rootDir = root,
                    filename = "missing.md",
                    secureWipe = { secureWipeInvoked = true },
                )

                secureWipeInvoked.shouldBeFalse()
            }
        }
    }
}
