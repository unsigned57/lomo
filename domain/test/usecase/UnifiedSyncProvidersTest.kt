package com.lomo.domain.usecase

import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeS3SyncRepository
import com.lomo.domain.testing.fakes.FakeWebDavSyncRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: WebDavUnifiedSyncProvider and S3UnifiedSyncProvider
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: unified provider operations route remote-file backends to their repository-owned sync lifecycle.
 *
 * Scenarios:
 * - Given WebDAV pending changes are processed, when the unified provider runs, then it delegates to WebDAV sync.
 * - Given S3 pending changes are processed, when the unified provider runs, then it delegates to S3 sync without exposing remote-index policy.
 *
 * Observable outcomes:
 * - Returned unified result message and repository sync invocation.
 *
 * TDD proof:
 * - RED: before the fix these scenarios return fixed "No pending ... changes" successes and never call the
 *   provider repositories.
 *
 * Excludes:
 * - data-layer transport, planner internals, conflict resolution, and UI state rendering.
 */
class UnifiedSyncProvidersTest : DomainFunSpec() {
    init {
        test("given webdav pending changes when unified provider syncs then repository lifecycle runs") {
            runTest {
                val repository = FakeWebDavSyncRepository()
                repository.nextSyncResult = WebDavSyncResult.Success("WebDAV sync completed")
                val provider = WebDavUnifiedSyncProvider(repository)

                val result = provider.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)

                result shouldBe
                    UnifiedSyncResult.Success(
                        provider = SyncBackendType.WEBDAV,
                        message = "WebDAV sync completed",
                    )
                repository.syncCallCount shouldBe 1
            }
        }

        test("given s3 pending changes when unified provider syncs then repository lifecycle runs") {
            runTest {
                val repository = FakeS3SyncRepository()
                repository.nextSyncResult = S3SyncResult.Success("S3 sync completed")
                val provider = S3UnifiedSyncProvider(repository)

                val result = provider.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)

                result shouldBe
                    UnifiedSyncResult.Success(
                        provider = SyncBackendType.S3,
                        message = "S3 sync completed",
                    )
                repository.syncCallCount shouldBe 1
            }
        }
    }
}
