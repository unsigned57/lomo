package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: RemoteSyncCenterModels (P5-10 presentation facts)
 * - Owning layer: domain
 * - Priority tier: P1
 * - Capability: Sync Center host models for conflict list/detail shells.
 *
 * Scenarios:
 * - Given kind=binary, when isBinary is read, then true and isMarkdown false.
 * - Given kind=markdown (case-insensitive), when isMarkdown is read, then true.
 * - Given resolution kind constants, when compared, then keep_local / keep_remote /
 *   merged_body / skip_for_now wire names hold.
 *
 * Observable outcomes: kind helpers and resolution kind constants.
 * Excludes: repository IO, Compose, production DI.
 */
class RemoteSyncCenterModelsTest : DomainFunSpec() {
    init {
        test("binary kind marks isBinary and rejects markdown helper") {
            val path =
                RemoteSyncConflictPath(
                    path = "media/a.bin",
                    kind = "binary",
                    localDigest = "aa",
                    remoteDigest = "bb",
                    baselineDigest = "cc",
                    remoteTokenPresent = true,
                    localArtifactRef = "art-l",
                    remoteArtifactRef = "art-r",
                    status = RemoteSyncConflictPathStatus.Open,
                )
            path.isBinary shouldBe true
            path.isMarkdown shouldBe false
        }

        test("markdown kind is case-insensitive") {
            val path =
                RemoteSyncConflictPath(
                    path = "memo/a.md",
                    kind = "Markdown",
                    localDigest = null,
                    remoteDigest = null,
                    baselineDigest = null,
                    remoteTokenPresent = false,
                    localArtifactRef = null,
                    remoteArtifactRef = null,
                    status = RemoteSyncConflictPathStatus.Open,
                )
            path.isMarkdown shouldBe true
            path.isBinary shouldBe false
        }

        test("resolution kind wire constants match durable owner names") {
            RemoteSyncConflictResolution.KIND_KEEP_LOCAL shouldBe "keep_local"
            RemoteSyncConflictResolution.KIND_KEEP_REMOTE shouldBe "keep_remote"
            RemoteSyncConflictResolution.KIND_MERGED_BODY shouldBe "merged_body"
            RemoteSyncConflictResolution.KIND_SKIP_FOR_NOW shouldBe "skip_for_now"
        }
    }
}
