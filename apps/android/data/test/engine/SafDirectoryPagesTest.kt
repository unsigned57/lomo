/*
 * Behavior Contract:
 * Capability: bounded lifetime for SAF directory observations; owner: data; priority: P1.
 * Scenarios:
 * - Given sixteen unfinished listings, when another starts, then the oldest cursor becomes
 *   explicitly stale and the newest still returns its exact remaining metadata.
 * - Given metadata exceeding the per-directory budget, when captured, then resource_limit
 *   surfaces before a complete listing can be published.
 * - Given a valid snapshot but an out-of-range bound or page size, when resumed, then the
 *   boundary rejects it without consuming the valid continuation.
 * Observable outcomes: exact returned rows and classified cursor/resource failures.
 * TDD proof: companion gateway regression RED in workspace-scaling/pagination-red.stdout;
 * GREEN: ./kotlin test --include-module=data (workspace-scaling/pagination-green.stdout).
 * Excludes: physical-device memory measurements and provider-specific metadata allocation.
 */
package com.lomo.data.engine

import com.lomo.data.testing.DataFunSpec
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

class SafDirectoryPagesTest : DataFunSpec() {
    init {
        test(
            "given sixteen pending listings when a seventeenth begins then the oldest is stale and the newest completes",
        ) {
            val pages = SafDirectoryPages()
            val rows = (0..512).map(::snapshot)
            val old = requireNotNull(pages.first("tree", WorkspaceTarget.Root, rows, 512u).nextCursor)
            var latest = old
            repeat(16) {
                latest = requireNotNull(pages.first("tree", WorkspaceTarget.Root, rows, 512u).nextCursor)
            }
            shouldThrow<PlatformActionAccessException> {
                pages.resume("tree", WorkspaceTarget.Root, old, 512u)
            }.code shouldBe "stale_directory_cursor"
            val last = pages.resume("tree", WorkspaceTarget.Root, latest, 512u)
            last.items shouldBe listOf(snapshot(512))
            last.nextCursor shouldBe null
        }

        test("given oversized metadata when captured then no successful partial listing is published") {
            val pages = SafDirectoryPages()
            val huge = snapshot(0).copy(documentId = "x".repeat(9 * 1024 * 1024))
            val failure = shouldThrow<PlatformActionAccessException> { pages.capture(sequenceOf(huge)) }
            failure.category shouldBe "resource_limit"
            failure.code shouldBe "directory_snapshot_too_large"
        }

        test("given invalid bounds or sizes when resumed then the real continuation remains usable") {
            val pages = SafDirectoryPages()
            val rows = (0..512).map(::snapshot)
            val cursor = requireNotNull(pages.first("tree", WorkspaceTarget.Root, rows, 512u).nextCursor)
            for (offset in listOf("0", "513", "999999999999")) {
                shouldThrow<PlatformActionAccessException> {
                    pages.resume("tree", WorkspaceTarget.Root, cursor.substringBeforeLast('.') + ".$offset", 512u)
                }.code shouldBe "invalid_page_cursor"
            }
            for (size in listOf(0u, 513u, UInt.MAX_VALUE)) {
                shouldThrow<PlatformActionAccessException> {
                    pages.resume("tree", WorkspaceTarget.Root, cursor, size)
                }.code shouldBe "invalid_page_size"
            }
            pages.resume("tree", WorkspaceTarget.Root, cursor, 512u).items shouldBe listOf(snapshot(512))
        }
    }
}

private fun snapshot(index: Int) = PlatformDocumentSnapshot(
    target = WorkspaceTarget.Relative("$index.md"),
    kind = DocumentKind.FILE,
    mimeType = "text/markdown",
    length = 128uL,
    lastModifiedEpochMillis = 1000L,
    documentId = "opaque-$index",
    digest = null,
    displayName = "$index.md",
)
