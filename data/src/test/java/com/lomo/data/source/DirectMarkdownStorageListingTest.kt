package com.lomo.data.source

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/*
 * Test Contract:
 * - Unit under test: DirectMarkdownStorageListing (direct File-backed markdown scan)
 * - Behavior focus: recursive traversal of the main markdown root for metadata discovery.
 * - Observable outcomes: returned FileMetadata relative filenames for nested markdown files.
 * - Red phase: "directListMetadata returns metadata for nested markdown files" fails before the fix because the listing only called rootDir.listFiles() at the top level.
 * - Excludes: SAF-backed listing, UI rendering, sync engine behavior.
 */
class DirectMarkdownStorageListingTest {
    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `directListMetadata returns metadata for nested markdown files`() =
        runTest {
            val root = tempFolder.newFolder("lomo-metadata")
            root.resolve("a.md").writeText("x")
            val sub = root.resolve("sub")
            sub.mkdirs()
            sub.resolve("b.md").writeText("y")

            val metadata = directListMetadata(root)

            val names = metadata.map { it.filename }.toSet()
            assertEquals(setOf("a.md", "sub/b.md"), names)
        }
}
