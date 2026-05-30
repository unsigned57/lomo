package com.lomo.data.repository

import com.lomo.data.local.dao.PendingSyncReviewDao
import com.lomo.data.local.entity.PendingSyncReviewEntity
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
import com.lomo.domain.repository.WorkspaceSyncGeneration
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: RoomPendingSyncReviewStore
 * - Capability: Persist pending sync review sessions through a descriptor-only review-owned table and payload.
 * - Scenarios:
 *   - Given a pending review session, when written, then raw local/incoming markdown is not stored in Room JSON.
 *   - Given multiple backend sessions, when one backend is cleared, then only that review session is removed.
 * - Observable outcomes: descriptor metadata, absent raw content in payload JSON, and backend-scoped clear behavior.
 * - TDD proof: Fails before the fix because review payload JSON contains full localContent and incomingContent strings.
 * - Excludes: Room framework internals, sync repository orchestration, and UI rendering.
 */
class PendingSyncReviewStoreTest : DataFunSpec() {
    init {
        test("write stores descriptor without full review content") {
            `write stores descriptor without full review content`()
        }

        test("clear removes only targeted backend review session") {
            `clear removes only targeted backend review session`()
        }
    }

    private fun `write stores descriptor without full review content`() =
        runTest {
            val dao = FakePendingSyncReviewDao()
            val store = RoomPendingSyncReviewStore(dao, ReviewStoreTestGenerationProvider())
            val largeLocal = "local-review-content-".repeat(512)
            val largeIncoming = "incoming-review-content-".repeat(512)
            val review =
                reviewSession(
                    source = SyncBackendType.INBOX,
                    path = "inbox/2026_05_26.md",
                    localContent = largeLocal,
                    incomingContent = largeIncoming,
                )

            store.write(review)

            val descriptor = requireNotNull(store.readDescriptor(SyncBackendType.INBOX))
            descriptor.source shouldBe SyncBackendType.INBOX
            descriptor.items.single().relativePath shouldBe "inbox/2026_05_26.md"
            descriptor.items.single().state shouldBe SyncReviewItemState.READY_TO_IMPORT
            descriptor.items.single().local.size shouldBe largeLocal.toByteArray(Charsets.UTF_8).size.toLong()
            descriptor.items.single().incoming.size shouldBe largeIncoming.toByteArray(Charsets.UTF_8).size.toLong()
            descriptor.items.single().local.etag shouldBe descriptor.items.single().local.contentHash
            descriptor.items.single().incoming.etag shouldBe descriptor.items.single().incoming.contentHash
            dao.payloadFor(SyncBackendType.INBOX).shouldNotContain(largeLocal)
            dao.payloadFor(SyncBackendType.INBOX).shouldNotContain(largeIncoming)
        }

    private fun `clear removes only targeted backend review session`() =
        runTest {
            val store = RoomPendingSyncReviewStore(FakePendingSyncReviewDao(), ReviewStoreTestGenerationProvider())
            val inboxReview = reviewSession(source = SyncBackendType.INBOX, path = "inbox/2026_05_26.md")
            val webDavReview = reviewSession(source = SyncBackendType.WEBDAV, path = "lomo/memo/remote.md")
            store.write(inboxReview)
            store.write(webDavReview)

            store.clear(SyncBackendType.INBOX)

            store.readDescriptor(SyncBackendType.INBOX).shouldBeNull()
            store.readDescriptor(SyncBackendType.WEBDAV)?.source shouldBe webDavReview.source
        }

    private fun reviewSession(
        source: SyncBackendType,
        path: String,
        localContent: String = "local",
        incomingContent: String = "incoming",
    ): SyncReviewSession =
        SyncReviewSession(
            source = source,
            items =
                listOf(
                    SyncReviewItem(
                        relativePath = path,
                        localContent = localContent,
                        incomingContent = incomingContent,
                        isBinary = false,
                        localLastModified = 10L,
                        incomingLastModified = 20L,
                        state = SyncReviewItemState.READY_TO_IMPORT,
                        message = "ready",
                    ),
                ),
            timestamp = 123L,
            kind = SyncReviewSessionKind.SYNC_INBOX_IMPORT_REVIEW,
        )
}

private class FakePendingSyncReviewDao : PendingSyncReviewDao {
    private val entries = linkedMapOf<Pair<String, String>, PendingSyncReviewEntity>()

    override suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncReviewEntity? = entries[workspaceGeneration to backend]

    override suspend fun upsert(entity: PendingSyncReviewEntity) {
        entries[entity.workspaceGeneration to entity.backend] = entity
    }

    override suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    ) {
        entries.remove(workspaceGeneration to backend)
    }

    fun payloadFor(source: SyncBackendType): String =
        requireNotNull(entries["workspace-test" to source.name]).payloadJson
}

private class ReviewStoreTestGenerationProvider : WorkspaceSyncGenerationProvider {
    override suspend fun activeGeneration(): WorkspaceSyncGeneration = WorkspaceSyncGeneration("workspace-test")
}
