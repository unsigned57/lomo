/*
 * Behavior Contract:
 * Capability: one bounded SAF metadata snapshot per directory enumeration; owner: data; priority: P1.
 * Scenarios:
 * - Given 4097 sibling documents, when all 512-row pages are read, then every document appears
 *   exactly once with one provider query, including when the live directory changes between pages.
 * - Given a completed enumeration, when a new enumeration begins, then external additions appear.
 * - Given a foreign, malformed or finished cursor, when resumed, then an explicit cursor error
 *   is returned before querying the provider.
 * Observable outcomes: document identities, provider query count and classified failures.
 * TDD proof: RED/GREEN: ./kotlin test --include-module=data --include-classes='*SafDirectoryPaginationTest'
 * Excludes: physical-device latency, provider atomicity within its own cursor and document writes.
 * Test Change Justification:
 * Reason category: behavior-preserving provider fixture refactor.
 * Old behavior/assertion being replaced: The metadata cursor now models rows, position and close state directly.
 * Why old assertion is no longer correct: existing outcome assertions remain valid and retained.
 * Coverage preserved by: The 4097-row snapshot, fresh enumeration and malformed/stale cursor assertions remain.
 * Why this is not fitting the test to the implementation: no expected product outcome is weakened.
 */
package com.lomo.data.engine

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeCursor
import com.lomo.nativebridge.WorkspaceTarget
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic

class SafDirectoryPaginationTest : DataFunSpec() {
    init {
        afterTest {
            unmockkStatic(Uri::class)
            unmockkStatic(DocumentsContract::class)
        }

        test("given 4097 siblings when paging across external additions then one snapshot emits each original once") {
            val fixture = DirectoryProvider(4097)
            val first = fixture.page()
            fixture.names = listOf("external.md") + fixture.names.reversed()
            val names = first.items.map { it.displayName }.toMutableList()
            var cursor = first.nextCursor
            while (cursor != null) {
                val page = fixture.page(cursor)
                names += page.items.map { it.displayName }
                cursor = page.nextCursor
            }
            names shouldBe (0 until 4097).map { "$it.md" }
            fixture.queries shouldBe 1
            val fresh = fixture.page()
            fresh.items.first().displayName shouldBe "external.md"
            fixture.queries shouldBe 2
        }

        test("given a foreign directory cursor when resumed then no unrelated provider query occurs") {
            val fixture = DirectoryProvider(513)
            val cursor = requireNotNull(fixture.page().nextCursor)
            val failure = shouldThrow<PlatformActionAccessException> {
                fixture.gateway.listChildren(TREE, WorkspaceTarget.Relative("other"), cursor, 512u)
            }
            failure.code shouldBe "invalid_page_cursor"
            fixture.queries shouldBe 1
        }

        test("given malformed cursors when resumed then offsets never silently restart enumeration") {
            val fixture = DirectoryProvider(3)
            for (cursor in listOf("1", "garbage", "-1", "s1.missing.512")) {
                val failure = shouldThrow<PlatformActionAccessException> { fixture.page(cursor) }
                failure.code shouldBe "invalid_page_cursor"
            }
            fixture.queries shouldBe 0
        }

        test("given a finished snapshot when resumed then released evidence is explicitly stale") {
            val fixture = DirectoryProvider(513)
            val cursor = requireNotNull(fixture.page().nextCursor)
            fixture.page(cursor).items.size shouldBe 1
            val failure = shouldThrow<PlatformActionAccessException> { fixture.page(cursor) }
            failure.code shouldBe "stale_directory_cursor"
            fixture.queries shouldBe 1
        }
    }
}

private class DirectoryProvider(count: Int) {
    private val resolver = mockk<ContentResolver>()
    private val tree = mockk<Uri>()
    private val children = mockk<Uri>()
    val gateway = ContentResolverPlatformDocumentsGateway(resolver)
    var names = (0 until count).map { "$it.md" }
    var queries = 0

    init {
        mockkStatic(Uri::class)
        mockkStatic(DocumentsContract::class)
        every { Uri.parse(TREE) } returns tree
        every { DocumentsContract.getTreeDocumentId(tree) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(tree, "root") } returns children
        every { resolver.query(children, any<Array<String>>(), null, null, null) } answers {
            queries += 1
            metadataCursor(names.toList())
        }
    }

    fun page(cursor: String? = null) = gateway.listChildren(TREE, WorkspaceTarget.Root, cursor, 512u)
}

private fun metadataCursor(names: List<String>): Cursor = FakeCursor(
    ContentResolverPlatformDocumentsGateway.DOCUMENT_PROJECTION,
    names.map { name -> listOf("root/$name", name, "text/markdown", 128L, 128L) },
)

private const val TREE = "content://com.lomo.documents/tree/root"
