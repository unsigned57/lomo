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
import kotlinx.coroutines.flow.toList

/*
 * Behavior Contract:
 * - Unit under test: DirectMarkdownStorageListing (direct File-backed markdown scan)
 * - Behavior focus: recursive traversal and metadata streaming of the main markdown root.
 * - Observable outcomes: returned and streamed relative filenames for nested markdown files.
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

        test("directStreamMetadataWithIds streams nested markdown files without list API") {
            directStreamMetadataWithIdsStreamsNestedMarkdownFilesWithoutListApi()
        }
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

    private fun directStreamMetadataWithIdsStreamsNestedMarkdownFilesWithoutListApi() =
        runTest {
            val root = tempFolder.newFolder("lomo-stream-metadata")
            root.resolve("a.md").writeText("x")
            val sub = root.resolve("sub")
            sub.mkdirs()
            sub.resolve("b.md").writeText("y")

            val metadata = directStreamMetadataWithIds(root).toList()

            metadata.map { it.filename }.toSet() shouldBe setOf("a.md", "sub/b.md")
            metadata.associate { it.filename to it.documentId } shouldBe
                mapOf(
                    "a.md" to "a.md",
                    "sub/b.md" to "sub/b.md",
                )
        }
}
