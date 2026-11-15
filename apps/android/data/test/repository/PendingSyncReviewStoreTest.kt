package com.lomo.data.repository

import com.lomo.data.sync.pendingreview.FileBackedPendingReviewTable
import com.lomo.data.sync.pendingreview.PendingSyncReviewRecord
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
import com.lomo.domain.repository.WorkspaceSyncGeneration
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: FileBackedPendingSyncReviewStore and PendingSyncSideMetadata matchers
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: persist pending sync review sessions and explicit side descriptors keyed by active
 *   workspace generation and backend, treat NONE as a strict no-op, reject S3 binary reviews without
 *   explicit descriptors, decode unknown backends safely, and match remote/content metadata.
 * - Production scope: apps/android/data/src/repository/PendingSyncReviewStore.kt
 * - Production scope: apps/android/data/src/repository/PendingSyncSessionRestore.kt
 * - Scenarios:
 *   - Given SyncBackendType.NONE, when read/write/writeDescriptor/clear run, then no generation lookup
 *     or table mutation occurs.
 *   - Given an INBOX review session, when written and reloaded from disk, then side hashes, UTF-8
 *     byte sizes, timestamps, and PENDING_RELOAD validationStatus survive cleanly.
 *   - Given an S3 review session containing a binary item, when write runs, then it rejects with
 *     IllegalArgumentException, while writeDescriptor preserves explicit binary side metadata.
 *   - Given a persisted record whose backend string is unknown to this build, when read, then it
 *     maps to SyncBackendType.UNKNOWN without collapsing to NONE or throwing.
 *   - Given PendingSyncSideMetadata hints, when matched against remote facts and body text, then
 *     mismatches in etag, mtime, size, completeness, or MD5 hash return false.
 * - Observable outcomes: decoded PendingSyncReviewDescriptor fields, on-disk generation isolation,
 *   thrown IllegalArgumentException, and matcher boolean results.
 * - TDD proof: locks PendingSyncReviewStore and PendingSyncSideMetadata contracts that previously
 *   had zero host test coverage.
 * - Excludes: full SyncInboxRestore worker orchestration (covered in SyncInboxRepositoryImplTest).
 */
class PendingSyncReviewStoreTest :
    FunSpec({
        fun newHarness(): StoreHarness {
            val dir = Files.createTempDirectory("lomo-review-store").toFile().apply { deleteOnExit() }
            val table = FileBackedPendingReviewTable(rootDir = dir)
            val generation = MutableGenerationProvider("gen-alpha")
            val store = FileBackedPendingSyncReviewStore(table = table, generationProvider = generation)
            return StoreHarness(dir = dir, table = table, generation = generation, store = store)
        }

        test("given NONE backend when store methods run then generation and table are untouched") {
            runTest {
                val harness = newHarness()
                val session =
                    SyncReviewSession(
                        source = SyncBackendType.NONE,
                        items = emptyList(),
                        timestamp = 100L,
                        kind = SyncReviewSessionKind.SYNC_INBOX_IMPORT_REVIEW,
                    )
                val descriptor =
                    PendingSyncReviewDescriptor(
                        source = SyncBackendType.NONE,
                        workspaceGeneration = "gen-alpha",
                        kind = SyncReviewSessionKind.SYNC_INBOX_IMPORT_REVIEW,
                        items = emptyList(),
                        timestamp = 100L,
                        validationStatus = PendingSyncValidationStatus.VALIDATED,
                    )

                harness.store.readDescriptor(SyncBackendType.NONE).shouldBeNull()
                harness.store.write(session)
                harness.store.writeDescriptor(descriptor)
                harness.store.clear(SyncBackendType.NONE)

                harness.generation.calls shouldBe 0
            }
        }

        test("given inbox session when written then descriptor roundtrips hashes sizes and clears cleanly") {
            runTest {
                val harness = newHarness()
                val localBody = "local note body"
                val incomingBody = "incoming note body"
                val session =
                    SyncReviewSession(
                        source = SyncBackendType.INBOX,
                        items =
                        listOf(
                            SyncReviewItem(
                                relativePath = "2026-11-15.md",
                                localContent = localBody,
                                incomingContent = incomingBody,
                                isBinary = false,
                                localLastModified = 111L,
                                incomingLastModified = 222L,
                                state = SyncReviewItemState.CONTENT_DIFFERENCE,
                                message = "conflict pending",
                            ),
                        ),
                        timestamp = 999L,
                        kind = SyncReviewSessionKind.SYNC_INBOX_IMPORT_REVIEW,
                    )

                harness.store.write(session)

                val reloadedStore =
                    FileBackedPendingSyncReviewStore(
                        table = FileBackedPendingReviewTable(rootDir = harness.dir),
                        generationProvider = harness.generation,
                    )
                val descriptor = reloadedStore.readDescriptor(SyncBackendType.INBOX).shouldNotBeNull()

                descriptor.source shouldBe SyncBackendType.INBOX
                descriptor.workspaceGeneration shouldBe "gen-alpha"
                descriptor.kind shouldBe SyncReviewSessionKind.SYNC_INBOX_IMPORT_REVIEW
                descriptor.timestamp shouldBe 999L
                descriptor.validationStatus shouldBe PendingSyncValidationStatus.PENDING_RELOAD
                val item = descriptor.items.single()
                item.relativePath shouldBe "2026-11-15.md"
                item.local.size shouldBe localBody.toByteArray(Charsets.UTF_8).size.toLong()
                item.local.contentHash shouldBe localBody.toByteArray(Charsets.UTF_8).md5Hex()
                item.incoming.size shouldBe incomingBody.toByteArray(Charsets.UTF_8).size.toLong()
                item.incoming.contentHash shouldBe incomingBody.toByteArray(Charsets.UTF_8).md5Hex()
                item.state shouldBe SyncReviewItemState.CONTENT_DIFFERENCE
                item.message shouldBe "conflict pending"

                harness.generation.current = "gen-beta"
                reloadedStore.readDescriptor(SyncBackendType.INBOX).shouldBeNull()

                harness.generation.current = "gen-alpha"
                reloadedStore.clear(SyncBackendType.INBOX)
                reloadedStore.readDescriptor(SyncBackendType.INBOX).shouldBeNull()
            }
        }

        test("given s3 binary review session when write runs then rejected while writeDescriptor persists it") {
            runTest {
                val harness = newHarness()
                val binarySession =
                    SyncReviewSession(
                        source = SyncBackendType.S3,
                        items =
                        listOf(
                            SyncReviewItem(
                                relativePath = "media/photo.png",
                                localContent = null,
                                incomingContent = null,
                                isBinary = true,
                            ),
                        ),
                        timestamp = 500L,
                        kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
                    )

                shouldThrow<IllegalArgumentException> {
                    harness.store.write(binarySession)
                }

                val explicitDescriptor =
                    PendingSyncReviewDescriptor(
                        source = SyncBackendType.S3,
                        workspaceGeneration = "gen-alpha",
                        kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
                        items =
                        listOf(
                            PendingSyncReviewItemDescriptor(
                                relativePath = "media/photo.png",
                                isBinary = true,
                                local = PendingSyncSideMetadata(locator = "media/photo.png", size = 12L),
                                incoming =
                                PendingSyncSideMetadata(
                                    locator = "s3://bucket/photo.png",
                                    etag = "\"abc\"",
                                    size = 24L,
                                ),
                                state = SyncReviewItemState.CONTENT_DIFFERENCE,
                            ),
                        ),
                        timestamp = 501L,
                        validationStatus = PendingSyncValidationStatus.VALIDATED,
                    )
                harness.store.writeDescriptor(explicitDescriptor)

                val loaded = harness.store.readDescriptor(SyncBackendType.S3).shouldNotBeNull()
                loaded.items.single().incoming.etag shouldBe "\"abc\""
                loaded.items.single().isBinary shouldBe true
            }
        }

        test("given persisted record with future backend name when decoded then backend maps to UNKNOWN") {
            runTest {
                val unknownStore =
                    FileBackedPendingSyncReviewStore(
                        table =
                        object : com.lomo.data.sync.pendingreview.PendingReviewTable {
                            override suspend fun getByBackend(
                                backend: String,
                                workspaceGeneration: String,
                            ): PendingSyncReviewRecord = PendingSyncReviewRecord(
                                workspaceGeneration = workspaceGeneration,
                                backend = "FUTURE_CLOUD",
                                reviewKind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW.name,
                                timestamp = 42L,
                                payloadJson =
                                """{"schemaVersion":2,"validationStatus":"VALIDATED","items":[]}""",
                            )

                            override suspend fun upsert(record: PendingSyncReviewRecord) = error("unused")

                            override suspend fun deleteByBackend(backend: String, workspaceGeneration: String) =
                                error("unused")

                            override suspend fun clearAll() = error("unused")
                        },
                        generationProvider = MutableGenerationProvider("gen-alpha"),
                    )

                val decoded = unknownStore.readDescriptor(SyncBackendType.UNKNOWN).shouldNotBeNull()
                decoded.source shouldBe SyncBackendType.UNKNOWN
            }
        }

        test("given side metadata when compared with remote and content facts then mismatches are rejected") {
            val expectedContent = "sync-body"
            val meta =
                PendingSyncSideMetadata(
                    locator = "notes/a.md",
                    contentHash = expectedContent.toByteArray(Charsets.UTF_8).md5Hex(),
                    lastModified = 100L,
                    size = 9L,
                    etag = "e1",
                )

            meta.hasCompleteRemoteMetadata() shouldBe true
            meta.matchesRemote(actualEtag = "e1", actualLastModified = 100L, actualSize = 9L) shouldBe true
            meta.matchesRemote(actualEtag = "e2", actualLastModified = 100L, actualSize = 9L) shouldBe false
            meta.matchesRemote(actualEtag = null, actualLastModified = 101L, actualSize = 9L) shouldBe false
            meta.matchesRemote(actualEtag = null, actualLastModified = null, actualSize = 10L) shouldBe false
            meta.matchesContent(expectedContent) shouldBe true
            meta.matchesContent("tampered") shouldBe false

            PendingSyncSideMetadata(lastModified = 100L).hasCompleteRemoteMetadata() shouldBe false
            PendingSyncSideMetadata(lastModified = 100L, size = 10L).hasCompleteRemoteMetadata() shouldBe true
            PendingSyncSideMetadata(contentHash = null).matchesContent("anything") shouldBe true
        }
    })

private class StoreHarness(
    val dir: java.io.File,
    val table: FileBackedPendingReviewTable,
    val generation: MutableGenerationProvider,
    val store: FileBackedPendingSyncReviewStore,
)

private class MutableGenerationProvider(var current: String) : WorkspaceSyncGenerationProvider {
    var calls: Int = 0

    override suspend fun activeGeneration(): WorkspaceSyncGeneration {
        calls += 1
        return WorkspaceSyncGeneration(current)
    }
}
