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
 *
 * Observable outcomes:
 * - PlatformMetadataPage.incomplete, DirectRootAccessException codes, and durable file bytes.
 *
 * TDD proof:
 * - RED on 2026-09-16: listing skipped symlink children and returned a complete page, so a scan
 *   could treat a memo replaced by an escaping symlink as deleted.
 *
 * Excludes:
 * - SAF ContentResolver IO, session mount orchestration, and native StoreHandle lifecycle.
 */

package com.lomo.data.engine

import com.lomo.data.testing.DataFunSpec
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import io.kotest.assertions.throwables.shouldThrow
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
                        fixture.gateway.stat(fixture.grant, WorkspaceTarget.Relative(".."))
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
