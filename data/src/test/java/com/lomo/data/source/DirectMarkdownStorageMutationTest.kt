package com.lomo.data.source

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/*
 * Test Contract:
 * - Unit under test: direct markdown delete helpers
 * - Behavior focus: delete paths only invoke secure wipe when the overwrite-before-unlink option is enabled.
 * - Observable outcomes: target file removal plus injected secure-wipe callback invocation.
 * - Red phase: Fails before the fix because directDeleteFile/directDeleteTrashFile do not accept or honor an overwrite-before-unlink option.
 * - Excludes: SAF-backed deletion and low-level overwrite byte patterns.
 */
class DirectMarkdownStorageMutationTest {
    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `directDeleteFile skips secure wipe when overwrite-before-unlink is disabled`() =
        runTest {
            val root = tempFolder.newFolder("lomo-direct-delete")
            val target = root.resolve("2026_04_20.md").apply { writeText("secret") }
            var secureWipeInvoked = false

            directDeleteFile(
                rootDir = root,
                filename = target.name,
                overwriteBeforeUnlink = false,
                secureWipe = { secureWipeInvoked = true },
            )

            assertFalse(target.exists())
            assertFalse(secureWipeInvoked)
        }

    @Test
    fun `directDeleteTrashFile invokes secure wipe before unlink when enabled`() =
        runTest {
            val root = tempFolder.newFolder("lomo-direct-trash-delete")
            val trashDir = root.resolve(".trash").apply { mkdirs() }
            val target = trashDir.resolve("2026_04_20.md").apply { writeText("secret") }
            var secureWipeInvoked = false

            directDeleteTrashFile(
                rootDir = root,
                filename = target.name,
                overwriteBeforeUnlink = true,
                secureWipe = { file ->
                    secureWipeInvoked = true
                    assertTrue(file.exists())
                },
            )

            assertTrue(secureWipeInvoked)
            assertFalse(target.exists())
        }
}
