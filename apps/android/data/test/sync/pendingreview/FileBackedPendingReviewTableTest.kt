package com.lomo.data.sync.pendingreview

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: FileBackedPendingReviewTable (file-backed Sync Inbox pending-review table).
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: durable (workspaceGeneration, backend)-scoped pending-review records surviving
 *   process restarts; explicit delete and clear; corrupt table file is clean-slate discarded
 *   (documented production behavior contract).
 *
 * Scenarios:
 * - Given a fresh table, when a record is upserted and a new table instance loads the same
 *   directory, then getByBackend returns the record (durable across restart).
 * - Given two backends under one generation, when one is deleted, then only the other remains.
 * - Given records across backends and generations, when clearAll runs, then lookups miss and a
 *   fresh instance observes an empty table.
 * - Given a corrupt table file on disk, when a new table instance loads, then lookups miss and the
 *   next upsert persists cleanly.
 *
 * Observable outcomes: PendingSyncReviewRecord lookups on this and freshly loaded instances.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.sync.pendingreview.FileBackedPendingReviewTableTest'
 * - RED: FileBackedPendingReviewTable did not exist; the legacy dao/entity naming under
 *   data/local is deleted in the same change.
 *
 * Excludes:
 * - Real Android Context filesDir wiring (the constructor overload delegates to the same root).
 *
 * Test Change Justification:
 * - Reason category: legacy dao/entity naming tail deletion; the P5-13 boundary keeps the inbox
 *   pending-review table Kotlin-owned, so its durable contract gets its first locked test.
 */
class FileBackedPendingReviewTableTest : FunSpec({
    fun newTable(tablesDir: java.nio.file.Path): FileBackedPendingReviewTable =
        FileBackedPendingReviewTable(rootDir = tablesDir.toFile())

    fun record(
        backend: String,
        generation: String = "generation-1",
    ): PendingSyncReviewRecord =
        PendingSyncReviewRecord(
            workspaceGeneration = generation,
            backend = backend,
            reviewKind = "SYNC_INBOX_IMPORT_REVIEW",
            timestamp = 1_725_000_000_000,
            payloadJson = """{"schemaVersion":2,"items":[]}""",
        )

    test("upserted record survives a fresh table instance load") {
        runTest {
            val tablesDir = Files.createTempDirectory("lomo-pending-review")
            val table = newTable(tablesDir)
            table.upsert(record(backend = "INBOX"))

            val reloaded = newTable(tablesDir)

            reloaded.getByBackend(backend = "INBOX", workspaceGeneration = "generation-1") shouldBe
                record(backend = "INBOX")
        }
    }

    test("deleteByBackend removes only the targeted backend record") {
        runTest {
            val tablesDir = Files.createTempDirectory("lomo-pending-review")
            val table = newTable(tablesDir)
            table.upsert(record(backend = "INBOX"))
            table.upsert(record(backend = "GIT"))

            table.deleteByBackend(backend = "INBOX", workspaceGeneration = "generation-1")

            table.getByBackend(backend = "INBOX", workspaceGeneration = "generation-1") shouldBe null
            table.getByBackend(backend = "GIT", workspaceGeneration = "generation-1") shouldNotBe null
        }
    }

    test("clearAll removes records across backends and generations") {
        runTest {
            val tablesDir = Files.createTempDirectory("lomo-pending-review")
            val table = newTable(tablesDir)
            table.upsert(record(backend = "INBOX", generation = "generation-1"))
            table.upsert(record(backend = "INBOX", generation = "generation-2"))

            table.clearAll()

            table.getByBackend(backend = "INBOX", workspaceGeneration = "generation-1") shouldBe null
            val reloaded = newTable(tablesDir)
            reloaded.getByBackend(backend = "INBOX", workspaceGeneration = "generation-2") shouldBe null
        }
    }

    test("corrupt table file is clean-slate discarded and next upsert persists") {
        runTest {
            val tablesDir = Files.createTempDirectory("lomo-pending-review")
            Files.writeString(tablesDir.resolve("pending_reviews.json"), "{ not json")

            val table = FileBackedPendingReviewTable(tablesDir.toFile())
            table.getByBackend(backend = "INBOX", workspaceGeneration = "generation-1") shouldBe null

            table.upsert(record(backend = "INBOX"))
            val reloaded = FileBackedPendingReviewTable(tablesDir.toFile())
            reloaded.getByBackend(backend = "INBOX", workspaceGeneration = "generation-1").shouldNotBeNull()
        }
    }
})
