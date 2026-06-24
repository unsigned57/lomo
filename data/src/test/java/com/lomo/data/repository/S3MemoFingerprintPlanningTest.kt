package com.lomo.data.repository

import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.S3PutObjectResult
import com.lomo.data.s3.S3RemoteListPage
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3SmallObjectPayload
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * - Unit under test: S3 initial overlap classification and tracked memo refinement.
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: plan routine memo same/changed decisions from local streaming fingerprints and remote metadata/index fingerprints.
 *
 * Scenarios:
 * - Given an initial overlapping memo has a local fingerprint and remote content fingerprint, when initial sync classifies it, then it seeds unchanged metadata without local ByteArray or remote body comparison.
 * - Given a tracked memo has a local fingerprint, baseline fingerprint, and remote content fingerprint, when refinement runs, then same/changed outcomes are derived from fingerprints without remote body reads.
 * - Given a listed remote object carries metadata md5, when the remote index is produced and restored as a cached snapshot, then the content fingerprint survives the production mapping path.
 *
 * Observable outcomes:
 * - seeded metadata, refined S3SyncAction directions/reasons, mapped remote content fingerprints, and remote small-object call count.
 *
 * TDD proof:
 * - RED Report 10 item 6: current initial overlap and tracked refinement ignore local LocalS3File fingerprints, attempt ByteArray content fallback, and leave fingerprint-identifiable memos unresolved.
 *
 * Test Change Justification:
 * - Reason category: mechanical shape change required by a production contract change.
 * - Old behavior/assertion being replaced: classification/refinement consumed only eagerly listed
 *   LocalS3File fingerprints; calls had no fingerprint source argument.
 * - Why old assertion is no longer correct: enumeration is metadata-only now; planners resolve
 *   fingerprints on demand through a required S3LocalFingerprintSource boundary.
 * - Coverage preserved by: identical action/metadata assertions plus strict erroring sources proving
 *   provided fingerprints still win without content reads; on-demand resolution behavior is covered by
 *   S3LocalFingerprintResolutionTest.
 * - Why this is not fitting the test to the implementation: the observable outcomes (seeded metadata,
 *   refined actions, zero remote body reads) are unchanged; only the collaborator boundary is explicit.
 *
 * Excludes:
 * - S3 transfer execution, conflict-view materialization, Room migrations, WorkManager scheduling, and UI rendering.
 */
class S3MemoFingerprintPlanningTest : FunSpec({
    val layout = SyncDirectoryLayout(memoFolder = "memo", imageFolder = "images", voiceFolder = "voice", allSameDirectory = false)
    val noContentReads =
        S3LocalFingerprintSource { _, _ ->
            error("provided fingerprints must satisfy planning without local content reads")
        }

    test("given initial memo fingerprints when overlap is classified then unchanged metadata is seeded without remote body reads") {
        val path = "memo/note.md"
        val fingerprint = "0123456789abcdef0123456789abcdef"
        val client = CountingBodyReadS3Client()

        val classification =
            classifyInitialOverlaps(
                localFiles =
                    mapOf(
                        path to
                            LocalS3File(
                                path = path,
                                lastModified = 100L,
                                size = 42L,
                                localFingerprint = fingerprint,
                            ),
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
                mode = emptyFileVaultMode(),
                localFingerprintSource = noContentReads,
                timestampToleranceMs = 0L,
            )

        classification.equivalentMetadataByPath shouldContainKey path
        classification.equivalentMetadataByPath.getValue(path).localFingerprint shouldBe fingerprint
        classification.resolvedActionsByPath shouldBe emptyMap()
        client.getSmallObjectCalls shouldBe 0
    }

    test("given tracked memo fingerprints when local changed and remote matches baseline then refinement uploads without remote body reads") {
        val path = "memo/note.md"
        val baselineFingerprint = "11111111111111111111111111111111"
        val localFingerprint = "22222222222222222222222222222222"
        val client = CountingBodyReadS3Client()

        val refined =
            refineTrackedMemoPlanWithContent(
                plan =
                    S3SyncPlan(
                        actions = listOf(S3SyncAction(path, S3SyncDirection.CONFLICT, S3SyncReason.CONFLICT)),
                        pendingChanges = 1,
                    ),
                localFiles =
                    mapOf(
                        path to
                            LocalS3File(
                                path = path,
                                lastModified = 200L,
                                size = 42L,
                                localFingerprint = localFingerprint,
                            ),
                    ),
                remoteFiles =
                    mapOf(
                        path to
                            RemoteS3File(
                                path = path,
                                etag = "multipart-2",
                                lastModified = 100L,
                                size = 42L,
                                contentMd5 = baselineFingerprint,
                            ),
                    ),
                metadataByPath =
                    mapOf(
                        path to
                            metadata(path = path, localFingerprint = baselineFingerprint),
                    ),
                layout = layout,
                mode = emptyFileVaultMode(),
                localFingerprintSource = noContentReads,
            )

        refined.actions.shouldContainExactly(
            S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_NEWER),
        )
        client.getSmallObjectCalls shouldBe 0
    }

    test("given tracked memo fingerprints when local and remote both match baseline then refinement removes the action without remote body reads") {
        val path = "memo/note.md"
        val fingerprint = "33333333333333333333333333333333"
        val client = CountingBodyReadS3Client()

        val refined =
            refineTrackedMemoPlanWithContent(
                plan =
                    S3SyncPlan(
                        actions = listOf(S3SyncAction(path, S3SyncDirection.CONFLICT, S3SyncReason.CONFLICT)),
                        pendingChanges = 1,
                    ),
                localFiles =
                    mapOf(
                        path to
                            LocalS3File(
                                path = path,
                                lastModified = 200L,
                                size = 42L,
                                localFingerprint = fingerprint,
                            ),
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
                metadataByPath =
                    mapOf(
                        path to
                            metadata(path = path, localFingerprint = fingerprint),
                    ),
                layout = layout,
                mode = emptyFileVaultMode(),
                localFingerprintSource = noContentReads,
            )

        refined.actions.shouldBeEmpty()
        refined.pendingChanges shouldBe 0
        client.getSmallObjectCalls shouldBe 0
    }

    test("given listed remote metadata md5 when index snapshot is restored then cached remote keeps content fingerprint") {
        val path = "memo/note.md"
        val fingerprint = "44444444444444444444444444444444"

        val indexed =
            S3RemoteObject(
                key = path,
                eTag = "multipart-2",
                lastModified = 100L,
                size = 42L,
                metadata = mapOf("md5" to fingerprint),
            ).toRemoteIndexEntry(
                relativePath = path,
                now = 200L,
                scanEpoch = 7L,
            )
        val cached = indexed.toCachedRemoteFile()
        val verified = indexed.toVerifiedRemoteFile()

        indexed.contentMd5 shouldBe fingerprint
        cached.contentMd5 shouldBe fingerprint
        verified.contentMd5 shouldBe fingerprint
        cached.toRemoteSyncRemoteSnapshot().contentFingerprint shouldBe fingerprint
    }

    test("given remote object with embedded mtime when converting to index entry then it stores the resolved canonical mtime") {
        val path = "memo/note.md"
        val remoteObject =
            S3RemoteObject(
                key = path,
                eTag = "etag-1",
                lastModified = 9_000_000L, // raw HTTP store time, diverging from the app-embedded mtime
                size = 42L,
                metadata = mapOf("mtime" to "1700"), // app-embedded mtime: 1700s -> 1_700_000ms
            )

        val indexEntry = remoteObject.toRemoteIndexEntry(relativePath = path, now = 200L, scanEpoch = 7L)

        // The reconcile-written index must store the canonical resolved mtime (identical to the verified
        // remote file), so a scan stays within the planner's timestamp tolerance of the app-time baseline
        // instead of rewriting the index to raw HTTP time and triggering a spurious remote change.
        indexEntry.remoteLastModified shouldBe 1_700_000L
        indexEntry.remoteLastModified shouldBe
            remoteObject.toVerifiedRemoteFile(path, S3SyncEncodingSupport()).lastModified
    }
})

private fun emptyFileVaultMode(): S3LocalSyncMode.FileVaultRoot =
    S3LocalSyncMode.FileVaultRoot(
        rootDir = File("/tmp/s3-empty-vault-fingerprint-planning"),
        memoRelativeDir = "memo",
        imageRelativeDir = "images",
        voiceRelativeDir = "voice",
        legacyRemoteCompatibility = false,
    )

private fun metadata(
    path: String,
    localFingerprint: String,
): S3SyncMetadataEntity =
    S3SyncMetadataEntity(
        relativePath = path,
        remotePath = path,
        etag = "etag-baseline",
        remoteLastModified = 100L,
        localLastModified = 100L,
        localSize = 42L,
        remoteSize = 42L,
        localFingerprint = localFingerprint,
        lastSyncedAt = 100L,
        lastResolvedDirection = S3SyncMetadataEntity.NONE,
        lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
    )

private class CountingBodyReadS3Client : LomoS3Client {
    var getSmallObjectCalls: Int = 0
        private set

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? =
        error("Routine memo planning should not HEAD in this focused fingerprint test")

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = error("Routine memo planning should not list in this focused fingerprint test")

    override suspend fun listPage(
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): S3RemoteListPage = error("Routine memo planning should not list in this focused fingerprint test")

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload {
        getSmallObjectCalls += 1
        error("Routine memo planning should not read remote memo bodies")
    }

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult = error("Routine memo planning should not write remote objects")

    override suspend fun getObjectToFile(
        key: String,
        destination: File,
    ): S3RemoteObject = error("Routine memo planning should not download remote objects")

    override suspend fun putObjectFile(
        key: String,
        file: File,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult = error("Routine memo planning should not write remote objects")

    override suspend fun deleteObject(key: String) {
        error("Routine memo planning should not delete remote objects")
    }

    override fun close() = Unit
}
