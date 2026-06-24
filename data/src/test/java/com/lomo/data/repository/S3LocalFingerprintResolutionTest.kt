package com.lomo.data.repository

import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: tracked memo refinement and initial overlap classification local fingerprint resolution.
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: local file enumeration stays metadata-only; content fingerprints are resolved on demand,
 *   served from the persisted sync-metadata (lastModified, size) cache first, and computed from content
 *   only as a last resort through one bridge-owned source.
 *
 * Scenarios:
 * - Given a stat-only local memo whose (lastModified, size) match the persisted metadata fingerprint cache,
 *   when a remote rewrite carries identical content, then refinement drops the action without reading content.
 * - Given a stat-only local memo whose stat differs from the persisted metadata, when refinement needs the
 *   fingerprint, then content is resolved exactly once through the fingerprint source.
 * - Given a stat-only initial overlap memo, when the remote side carries an identical content fingerprint,
 *   then classification resolves the local fingerprint on demand and seeds equivalent metadata uniformly
 *   across vault modes.
 * - Given a non-memo overlap whose sizes already diverge, when classification decides, then the fingerprint
 *   source is never consulted.
 *
 * Observable outcomes:
 * - refined S3SyncAction set, seeded equivalent metadata, and fingerprint-source invocation counts.
 *
 * TDD proof:
 * - Cache scenario failed RED with the action surviving refinement (actions not empty) because stat-only
 *   local files lost refinement entirely; classification scenario failed RED with no equivalent metadata
 *   seeded for stat-only overlaps.
 *
 * Excludes: S3 transfer execution, remote HEAD/GET behavior, Room migrations, and UI rendering.
 */
class S3LocalFingerprintResolutionTest : FunSpec({
    val layout =
        SyncDirectoryLayout(
            memoFolder = "memo",
            imageFolder = "images",
            voiceFolder = "voice",
            allSameDirectory = false,
        )
    val path = "memo/note.md"
    val fingerprint = "0123456789abcdef0123456789abcdef"

    test("given matching metadata cache when remote rewrite has identical content then refinement drops the action without reading content") {
        runTest {
            val refined =
                refineTrackedMemoPlanWithContent(
                    plan =
                        S3SyncPlan(
                            actions = listOf(S3SyncAction(path, S3SyncDirection.DOWNLOAD, S3SyncReason.REMOTE_NEWER)),
                            pendingChanges = 1,
                        ),
                    localFiles =
                        mapOf(
                            path to LocalS3File(path = path, lastModified = 100L, size = 42L),
                        ),
                    remoteFiles =
                        mapOf(
                            path to
                                RemoteS3File(
                                    path = path,
                                    etag = "rewritten-etag",
                                    lastModified = 900L,
                                    size = 42L,
                                    contentMd5 = fingerprint,
                                ),
                        ),
                    metadataByPath = mapOf(path to cachedMetadata(path, fingerprint)),
                    layout = layout,
                    mode = fingerprintResolutionVaultMode(),
                    localFingerprintSource = { _, _ ->
                        error("local content must not be read when the metadata fingerprint cache matches")
                    },
                )

            refined.actions.shouldBeEmpty()
            refined.pendingChanges shouldBe 0
        }
    }

    test("given stale metadata stat when refinement needs the local fingerprint then content is resolved once through the source") {
        runTest {
            val baseline = "11111111111111111111111111111111"
            val changedFingerprint = "22222222222222222222222222222222"
            var sourceReads = 0
            val refined =
                refineTrackedMemoPlanWithContent(
                    plan =
                        S3SyncPlan(
                            actions = listOf(S3SyncAction(path, S3SyncDirection.CONFLICT, S3SyncReason.CONFLICT)),
                            pendingChanges = 1,
                        ),
                    localFiles =
                        mapOf(
                            path to LocalS3File(path = path, lastModified = 900L, size = 57L),
                        ),
                    remoteFiles =
                        mapOf(
                            path to
                                RemoteS3File(
                                    path = path,
                                    etag = "etag-baseline",
                                    lastModified = 100L,
                                    size = 42L,
                                    contentMd5 = baseline,
                                ),
                        ),
                    metadataByPath = mapOf(path to cachedMetadata(path, baseline)),
                    layout = layout,
                    mode = fingerprintResolutionVaultMode(),
                    localFingerprintSource = { requestedPath, _ ->
                        requestedPath shouldBe path
                        sourceReads += 1
                        changedFingerprint
                    },
                )

            refined.actions.shouldContainExactly(
                S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_NEWER),
            )
            sourceReads shouldBe 1
        }
    }

    test("given stat-only initial overlap memo when remote carries identical content fingerprint then classification seeds equivalent metadata") {
        runTest {
            val classification =
                classifyInitialOverlaps(
                    localFiles =
                        mapOf(
                            path to LocalS3File(path = path, lastModified = 100L, size = 42L),
                        ),
                    remoteFiles =
                        mapOf(
                            path to
                                RemoteS3File(
                                    path = path,
                                    etag = "multipart-2",
                                    lastModified = 100L,
                                    size = 42L,
                                    contentMd5 = fingerprint,
                                ),
                        ),
                    metadataByPath = emptyMap(),
                    layout = layout,
                    mode = fingerprintResolutionVaultMode(),
                    localFingerprintSource = { _, _ -> fingerprint },
                    timestampToleranceMs = 0L,
                )

            classification.equivalentMetadataByPath shouldContainKey path
            classification.equivalentMetadataByPath.getValue(path).localFingerprint shouldBe fingerprint
            classification.resolvedActionsByPath shouldBe emptyMap()
        }
    }

    test("given non-memo initial overlap when sizes differ then classification decides without consulting the fingerprint source") {
        runTest {
            val imagePath = "images/photo.jpg"
            val classification =
                classifyInitialOverlaps(
                    localFiles =
                        mapOf(
                            imagePath to LocalS3File(path = imagePath, lastModified = 900L, size = 10L),
                        ),
                    remoteFiles =
                        mapOf(
                            imagePath to
                                RemoteS3File(
                                    path = imagePath,
                                    etag = "etag",
                                    lastModified = 100L,
                                    size = 99L,
                                    contentMd5 = fingerprint,
                                ),
                        ),
                    metadataByPath = emptyMap(),
                    layout = layout,
                    mode = fingerprintResolutionVaultMode(),
                    localFingerprintSource = { _, _ ->
                        error("size-divergent overlaps must not read local content")
                    },
                    timestampToleranceMs = 0L,
                )

            classification.resolvedActionsByPath.getValue(imagePath).direction shouldBe S3SyncDirection.UPLOAD
        }
    }
})

private fun cachedMetadata(
    path: String,
    fingerprint: String,
): S3SyncMetadataEntity =
    S3SyncMetadataEntity(
        relativePath = path,
        remotePath = path,
        etag = "etag-baseline",
        remoteLastModified = 100L,
        localLastModified = 100L,
        localSize = 42L,
        remoteSize = 42L,
        localFingerprint = fingerprint,
        lastSyncedAt = 100L,
        lastResolvedDirection = S3SyncMetadataEntity.NONE,
        lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
    )

private fun fingerprintResolutionVaultMode(): S3LocalSyncMode.FileVaultRoot =
    S3LocalSyncMode.FileVaultRoot(
        rootDir = File("/tmp/s3-empty-vault-fingerprint-resolution"),
        memoRelativeDir = "memo",
        imageRelativeDir = "images",
        voiceRelativeDir = "voice",
        legacyRemoteCompatibility = false,
    )
