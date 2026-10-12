/*
 * Behavior Contract:
 * - Unit under test: DirectRootDocumentsGateway.
 * - Owning layer: data Android Direct filesystem capability edge.
 * - Priority tier: P0.
 * - Capability: execute document IO against a registered Direct root by walking one path segment
 *   at a time with NOFOLLOW, without treating canonicalize()+startsWith(root) as a capability, and
 *   without publishing a complete empty listing when the directory cannot be trusted.
 *
 * Scenarios:
 * - Given an empty directory, when children are listed, then the page is complete and empty.
 * - Given a missing relative directory, when children are listed, then the page is incomplete.
 * - Given a symlink child pointing outside the root, when children are listed, then the page is
 *   incomplete and the outside target is not read.
 * - Given a relative path whose segment is `..` or a symlink, when Stat/open runs, then traversal
 *   is rejected before following the link.
 * - Given a CREATE write, when it completes, then the bound file contains the exchanged bytes.
 * - Given a directory of files, when children are listed or a move receipt is observed, then
 *   digests stay unknown because no content channel is opened for metadata observations.
 * - Given a file, when stat requests CONTENT then the digest is the streamed SHA-256, and when it
 *   requests METADATA_ONLY then the digest stays unknown.
 * - Given more children than one page, when pages are walked by cursor, then no child repeats and
 *   none is missed and the emitted order is the sorted name order.
 * - Given a child added or removed between pages, when the next page runs, then the minted cursor
 *   fails closed stale instead of silently re-paging a shifted name set.
 * - Given a malformed, legacy decimal, cross-directory or cross-grant cursor, when listing runs,
 *   then it fails closed invalid instead of being read as an offset.
 * - Given only child bytes change between pages, when the next page runs, then the bound still
 *   validates because membership — not content — is the ordering context.
 * - Given a symlink that sorts past the first page, when its own page is listed, then it is still
 *   refused incomplete rather than silently dropped or followed.
 *
 * Observable outcomes:
 * - PlatformMetadataPage.incomplete/items/nextCursor, DirectRootAccessException codes
 *   (invalid_page_cursor, stale_cursor), durable file bytes, and snapshot digest presence/absence
 *   per requested evidence depth.
 *
 * TDD proof:
 * - RED on 2026-09-16: listing skipped symlink children and returned a complete page, so a scan
 *   could treat a memo replaced by an escaping symlink as deleted.
 * - RED on 2026-10-04: every listed child and move receipt digested whole files because the
 *   snapshot builder always ran a CONTENT pass.
 * - RED on 2026-10-09 (P-43): the decimal-offset implementation accepted "2"/foreign/garbage
 *   cursors silently, kept paging after mid-scan membership changes, and refused the whole
 *   directory on page one instead of at the symlink's own page.
 *
 * Production scope: apps/android/data/src/engine/DirectRootDocumentsGateway.kt
 * Production scope: apps/android/data/src/engine/DirectListPageCursor.kt
 *
 * Excludes:
 * - SAF ContentResolver IO, session mount orchestration, and native StoreHandle lifecycle.
  * Test Change Justification:
 * - Reason category: production API signature change — stat now takes SnapshotDigestMode — plus
 *   new cursor-paging coverage for the directory listing contract.
 * - Old behavior/assertion being replaced: stat calls without an evidence-depth argument.
 * - Why old assertion is no longer correct: the gateway requires callers to declare the digest
 *   depth per stat; the old call shape does not compile.
 * - Coverage preserved by: traversal-rejection assertions now pass SnapshotDigestMode.CONTENT,
 *   and added scenarios pin digest-depth outcomes and cursor page semantics.
 * - Why this is not fitting the test to the implementation: the asserted safety outcomes
 *   (fail-closed traversal, complete pages) are unchanged; only the call shape migrated.
*/

package com.lomo.data.engine

import com.lomo.data.testing.DataFunSpec
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

class DirectRootDocumentsGatewayTest : DataFunSpec() {
    init {
        test("given an empty Direct root when children are listed then the page is complete and empty") {
            DirectRootFixture().use { fixture ->
                val page =
                    fixture.gateway.listChildren(
                        grant = fixture.grant,
                        target = WorkspaceTarget.Root,
                        cursor = null,
                        pageSize = 16u,
                    )

                page.incomplete shouldBe false
                page.items shouldBe emptyList()
                page.nextCursor shouldBe null
            }
        }

        test("given a missing relative directory when children are listed then the page is incomplete") {
            DirectRootFixture().use { fixture ->
                val page =
                    fixture.gateway.listChildren(
                        grant = fixture.grant,
                        target = WorkspaceTarget.Relative("absent-dir"),
                        cursor = null,
                        pageSize = 16u,
                    )

                page.incomplete shouldBe true
                page.items shouldBe emptyList()
            }
        }

        test("given a symlink child when children are listed then the page is incomplete and the target is unread") {
            DirectRootFixture().use { fixture ->
                val outside = Files.createTempFile("lomo-direct-outside", ".md")
                try {
                    Files.write(outside, "secret-outside".toByteArray(StandardCharsets.UTF_8))
                    Files.createSymbolicLink(fixture.root.toPath().resolve("escape.md"), outside)

                    val page =
                        fixture.gateway.listChildren(
                            grant = fixture.grant,
                            target = WorkspaceTarget.Root,
                            cursor = null,
                            pageSize = 16u,
                        )

                    page.incomplete shouldBe true
                    page.items shouldBe emptyList()
                    Files.readString(outside) shouldBe "secret-outside"
                } finally {
                    Files.deleteIfExists(outside)
                }
            }
        }

        test("given a `..` path segment when Stat runs then traversal is rejected") {
            DirectRootFixture().use { fixture ->
                val error =
                    shouldThrow<DirectRootAccessException> {
                        fixture.gateway.stat(
                            fixture.grant,
                            WorkspaceTarget.Relative(".."),
                            SnapshotDigestMode.CONTENT,
                        )
                    }
                error.category shouldBe "permission"
                error.code shouldBe "symlink_escape_rejected"
            }
        }

        test("given a symlink path when Stat runs then traversal is rejected without following") {
            DirectRootFixture().use { fixture ->
                val outside = Files.createTempDirectory("lomo-direct-outside-dir")
                try {
                    Files.writeString(outside.resolve("secret.md"), "secret-outside")
                    Files.createSymbolicLink(fixture.root.toPath().resolve("alias"), outside)

                    val error =
                        shouldThrow<DirectRootAccessException> {
                            fixture.gateway.stat(
                                fixture.grant,
                                WorkspaceTarget.Relative("alias/secret.md"),
                                SnapshotDigestMode.CONTENT,
                            )
                        }
                    error.category shouldBe "permission"
                    error.code shouldBe "symlink_escape_rejected"
                    Files.readString(outside.resolve("secret.md")) shouldBe "secret-outside"
                } finally {
                    outside.toFile().deleteRecursively()
                }
            }
        }

        test("given more children than one page when walked by cursor then no child repeats or is missed") {
            DirectRootFixture().use { fixture ->
                val expected = (0..8).map { index -> "child-%02d.md".format(index) }
                expected.forEach { name -> fixture.root.resolve(name).writeText("body-$name") }

                val seen = mutableListOf<String>()
                var cursor: String? = null
                var pages = 0
                do {
                    val page =
                        fixture.gateway.listChildren(
                            grant = fixture.grant,
                            target = WorkspaceTarget.Root,
                            cursor = cursor,
                            pageSize = 4u,
                        )
                    page.incomplete shouldBe false
                    seen += page.items.map { (it.target as WorkspaceTarget.Relative).path }
                    cursor = page.nextCursor
                    pages += 1
                } while (cursor != null && pages < 8)

                pages shouldBe 3
                seen shouldBe expected
            }
        }

        test("given a child added between pages when the next page runs then the minted cursor fails closed stale") {
            DirectRootFixture().use { fixture ->
                (0..5).forEach { index -> fixture.root.resolve("child-%02d.md".format(index)).writeText("x") }
                val first =
                    fixture.gateway.listChildren(
                        grant = fixture.grant,
                        target = WorkspaceTarget.Root,
                        cursor = null,
                        pageSize = 4u,
                    )
                first.nextCursor.shouldNotBeNull()

                fixture.root.resolve("child-9.md").writeText("late arrival")

                val error =
                    shouldThrow<DirectRootAccessException> {
                        fixture.gateway.listChildren(
                            grant = fixture.grant,
                            target = WorkspaceTarget.Root,
                            cursor = first.nextCursor,
                            pageSize = 4u,
                        )
                    }
                error.code shouldBe "stale_cursor"
            }
        }

        test("given a child removed between pages when the next page runs then the minted cursor fails closed stale") {
            DirectRootFixture().use { fixture ->
                (0..5).forEach { index -> fixture.root.resolve("child-%02d.md".format(index)).writeText("x") }
                val first =
                    fixture.gateway.listChildren(
                        grant = fixture.grant,
                        target = WorkspaceTarget.Root,
                        cursor = null,
                        pageSize = 4u,
                    )
                first.nextCursor.shouldNotBeNull()

                fixture.root.resolve("child-00.md").delete() shouldBe true

                val error =
                    shouldThrow<DirectRootAccessException> {
                        fixture.gateway.listChildren(
                            grant = fixture.grant,
                            target = WorkspaceTarget.Root,
                            cursor = first.nextCursor,
                            pageSize = 4u,
                        )
                    }
                error.code shouldBe "stale_cursor"
            }
        }

        test("given only child bytes change between pages when the next page runs then the bound still validates") {
            DirectRootFixture().use { fixture ->
                (0..4).forEach { index -> fixture.root.resolve("child-%02d.md".format(index)).writeText("x") }
                val first =
                    fixture.gateway.listChildren(
                        grant = fixture.grant,
                        target = WorkspaceTarget.Root,
                        cursor = null,
                        pageSize = 3u,
                    )
                first.nextCursor.shouldNotBeNull()

                fixture.root.resolve("child-00.md").writeText("rewritten bytes between pages")

                val second =
                    fixture.gateway.listChildren(
                        grant = fixture.grant,
                        target = WorkspaceTarget.Root,
                        cursor = first.nextCursor,
                        pageSize = 3u,
                    )
                second.incomplete shouldBe false
                second.items.map { (it.target as WorkspaceTarget.Relative).path } shouldBe
                    listOf("child-03.md", "child-04.md")
                second.nextCursor shouldBe null
            }
        }

        test("given malformed legacy or foreign cursors when listing then each fails closed invalid") {
            DirectRootFixture().use { fixture ->
                fixture.root.resolve("child-a.md").writeText("a")
                fixture.root.resolve("child-b.md").writeText("b")
                val foreignDirectory = fixture.root.resolve("sub")
                foreignDirectory.mkdirs() shouldBe true
                foreignDirectory.resolve("child-c.md").writeText("c")

                // Minted by this gateway for the root listing; must not open a different directory.
                val rootCursor =
                    fixture.gateway.listChildren(
                        grant = fixture.grant,
                        target = WorkspaceTarget.Root,
                        cursor = null,
                        pageSize = 1u,
                    ).nextCursor
                rootCursor.shouldNotBeNull()

                for (raw in listOf("2", "definitely-not-json", "{}")) {
                    val error =
                        shouldThrow<DirectRootAccessException> {
                            fixture.gateway.listChildren(
                                grant = fixture.grant,
                                target = WorkspaceTarget.Root,
                                cursor = raw,
                                pageSize = 4u,
                            )
                        }
                    error.code shouldBe "invalid_page_cursor"
                }

                val foreignDirectoryError =
                    shouldThrow<DirectRootAccessException> {
                        fixture.gateway.listChildren(
                            grant = fixture.grant,
                            target = WorkspaceTarget.Relative("sub"),
                            cursor = rootCursor,
                            pageSize = 4u,
                        )
                    }
                foreignDirectoryError.code shouldBe "invalid_page_cursor"
            }
        }

        test("given a cursor minted under another grant when listing then it fails closed invalid") {
            DirectRootFixture().use { first ->
                DirectRootFixture().use { second ->
                    (0..3).forEach { index -> first.root.resolve("child-%02d.md".format(index)).writeText("x") }
                    second.root.resolve("child-00.md").writeText("y")

                    val foreignCursor =
                        first.gateway.listChildren(
                            grant = first.grant,
                            target = WorkspaceTarget.Root,
                            cursor = null,
                            pageSize = 2u,
                        ).nextCursor
                    foreignCursor.shouldNotBeNull()

                    val error =
                        shouldThrow<DirectRootAccessException> {
                            second.gateway.listChildren(
                                grant = second.grant,
                                target = WorkspaceTarget.Root,
                                cursor = foreignCursor,
                                pageSize = 4u,
                            )
                        }
                    error.code shouldBe "invalid_page_cursor"
                }
            }
        }

        test("given a symlink past the first page when its own page is listed then it is refused incomplete") {
            DirectRootFixture().use { fixture ->
                val outside = Files.createTempFile("lomo-direct-outside", ".md")
                try {
                    Files.write(outside, "secret-outside".toByteArray(StandardCharsets.UTF_8))
                    fixture.root.resolve("child-a.md").writeText("a")
                    fixture.root.resolve("child-b.md").writeText("b")
                    fixture.root.resolve("child-c.md").writeText("c")
                    Files.createSymbolicLink(fixture.root.toPath().resolve("zz-escape.md"), outside)

                    val first =
                        fixture.gateway.listChildren(
                            grant = fixture.grant,
                            target = WorkspaceTarget.Root,
                            cursor = null,
                            pageSize = 2u,
                        )
                    first.incomplete shouldBe false
                    first.items.size shouldBe 2
                    first.nextCursor.shouldNotBeNull()

                    val second =
                        fixture.gateway.listChildren(
                            grant = fixture.grant,
                            target = WorkspaceTarget.Root,
                            cursor = first.nextCursor,
                            pageSize = 2u,
                        )
                    second.incomplete shouldBe true
                    second.items shouldBe emptyList()
                    Files.readString(outside) shouldBe "secret-outside"
                } finally {
                    Files.deleteIfExists(outside)
                }
            }
        }

        test("given populated children when listed then digests stay unknown without content channels") {
            DirectRootFixture().use { fixture ->
                fixture.root.resolve("one.md").writeText("one")
                fixture.root.resolve("two.md").writeText("two")

                val page =
                    fixture.gateway.listChildren(
                        grant = fixture.grant,
                        target = WorkspaceTarget.Root,
                        cursor = null,
                        pageSize = 16u,
                    )

                page.incomplete shouldBe false
                page.items.size shouldBe 2
                // A content pass would publish each file's SHA-256; a metadata page must not.
                page.items.forEach { it.digest shouldBe null }
            }
        }

        test("given a file when stat requests metadata then the digest stays unknown") {
            DirectRootFixture().use { fixture ->
                val bytes = "metadata-only".toByteArray(StandardCharsets.UTF_8)
                fixture.root.resolve("memo.md").writeBytes(bytes)

                val snapshot =
                    fixture.gateway.stat(
                        fixture.grant,
                        WorkspaceTarget.Relative("memo.md"),
                        SnapshotDigestMode.METADATA_ONLY,
                    )

                snapshot?.kind shouldBe DocumentKind.FILE
                snapshot?.length shouldBe bytes.size.toULong()
                snapshot?.digest shouldBe null
            }
        }

        test("given a file when stat requests content then the digest is the streamed hash") {
            DirectRootFixture().use { fixture ->
                val bytes = "content-evidence".toByteArray(StandardCharsets.UTF_8)
                fixture.root.resolve("memo.md").writeBytes(bytes)

                val snapshot =
                    fixture.gateway.stat(
                        fixture.grant,
                        WorkspaceTarget.Relative("memo.md"),
                        SnapshotDigestMode.CONTENT,
                    )

                snapshot?.digest shouldBe sha256Hex(bytes)
            }
        }

        test("given a move when it completes then the receipt stays metadata-only") {
            DirectRootFixture().use { fixture ->
                fixture.root.resolve("memo.md").writeText("moved bytes")

                val snapshot =
                    fixture.gateway.move(
                        grant = fixture.grant,
                        source = "memo.md",
                        target = "renamed.md",
                    )

                snapshot.documentId.isNotEmpty() shouldBe true
                snapshot.digest shouldBe null
                Files.readString(fixture.root.toPath().resolve("renamed.md")) shouldBe "moved bytes"
            }
        }

        test("given a CREATE write when it completes then the bound file contains those bytes") {
            DirectRootFixture().use { fixture ->
                val bytes = "hello-direct".toByteArray(StandardCharsets.UTF_8)
                val snapshot =
                    fixture.gateway.writeFromExchange(
                        grant = fixture.grant,
                        path = "memo.md",
                        bytes = bytes,
                        mode = WriteMode.CREATE,
                        mimeType = "text/markdown",
                    )

                snapshot.kind shouldBe DocumentKind.FILE
                snapshot.digest shouldBe sha256Hex(bytes)
                Files.readAllBytes(fixture.root.toPath().resolve("memo.md")) shouldBe bytes
            }
        }
    }
}

private class DirectRootFixture : AutoCloseable {
    val root = Files.createTempDirectory("lomo-direct-gateway").toFile()
    val grant = CapabilityRegistry().registerDirect(token = "cap-direct-gateway", rootPath = root)
    val gateway = DirectRootDocumentsGateway()

    override fun close() {
        root.deleteRecursively()
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
