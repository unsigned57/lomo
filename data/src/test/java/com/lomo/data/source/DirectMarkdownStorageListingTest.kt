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
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: DirectMarkdownStorageListing (direct File-backed markdown scan)
 * - Behavior focus: recursive traversal of the main markdown root for metadata discovery.
 * - Observable outcomes: returned FileMetadata relative filenames for nested markdown files.
 * - TDD proof: "directListMetadata returns metadata for nested markdown files" fails before the fix because the listing only called rootDir.listFiles() at the top level.
 * - Excludes: SAF-backed listing, UI rendering, sync engine behavior.
 */
class DirectMarkdownStorageListingTest : DataFunSpec() {
    init {
        beforeTest {
            tempFolder = KotestTemporaryFolder()
        }

        afterTest {
            tempFolder.cleanup()
        }

        test("directListMetadata returns metadata for nested markdown files") { `directListMetadata returns metadata for nested markdown files`() }
    }


    private lateinit var tempFolder: KotestTemporaryFolder
    private fun `directListMetadata returns metadata for nested markdown files`() =
        runTest {
            val root = tempFolder.newFolder("lomo-metadata")
            root.resolve("a.md").writeText("x")
            val sub = root.resolve("sub")
            sub.mkdirs()
            sub.resolve("b.md").writeText("y")

            val metadata = directListMetadata(root)

            val names = metadata.map { it.filename }.toSet()
            names shouldBe setOf("a.md", "sub/b.md")
        }
}
