package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot
import com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.s3.S3PutObjectResult
import com.lomo.data.s3.S3RemoteListPage
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3SmallObjectPayload
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.KotestTemporaryFolder
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewSession
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: S3SyncExecutor lifecycle stage payloads.
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: S3 lifecycle stages expose real semantic boundaries for snapshot loading,
 *   pure action planning, and conflict/review materialization.
 *
 * Scenarios:
 * - Given an S3 sync has local, remote, and metadata inputs, when loadSnapshot completes, then the
 *   stage-produced snapshot already contains those inputs for planning.
 * - Given planning and verification produce conflict actions, when materialization has not run, then
 *   prepared/verified state does not already contain the final conflict or review payload.
 * - Given materialization runs, when later stages consume the result, then the conflict/review payload
 *   is produced by materialization instead of hidden plan/verify side effects.
 *
 * Observable outcomes:
 * - Reflected stage payload fields contain snapshot paths, omit conflict/review state before
 *   materialization, and expose conflict state after materialization.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-classes='com.lomo.data.repository.S3SyncLifecycleStageBoundaryTest'`
 *   fails before the fix because S3 loadSnapshot only contains the client and PreparedS3Sync already
 *   carries conflict state before materializeConflicts runs.
 *
 * Excludes:
 * - AWS SDK transport, S3 action application, metadata commit, and UI conflict presentation.
 */

/*
 * Test Change Justification:
 * - Reason category: Signature update
 * - Old behavior/assertion being replaced: Fake S3 client collaborator putObjectFile and putSmallObject signatures without ifMatch and ifNoneMatch parameters.
 * - Why old assertion is no longer correct: The production S3 client interface has been upgraded with conditional write parameters for performance optimization.
 * - Coverage preserved by: All original test assertions are unchanged; the fake client is updated to compile against the new interface signature.
 * - Why this is not fitting the test to the implementation: This is a mechanical signature update to satisfy compile safety, not a change to the tested behavior.
 */
class S3SyncLifecycleStageBoundaryTest : DataFunSpec() {
    init {
        test("given s3 conflict sync when lifecycle reaches materialization then stage payloads own snapshot and conflicts") {
            runTest {
                val tempFolder = KotestTemporaryFolder()
                try {
                    val localRoot = tempFolder.newFolder("vault")
                    val localMemo = File(localRoot, "note.md")
                    localMemo.writeText("local")
                    localMemo.setLastModified(1_000L)
                    val remoteObject =
                        S3RemoteObject(
                            key = "note.md",
                            eTag = null,
                            lastModified = 1_000L,
                            size = 6L,
                            metadata = emptyMap(),
                        )
                    val runner = BoundaryProbeLifecycleRunner()
                    val executor =
                        createBoundaryExecutor(
                            localRoot = localRoot,
                            client = BoundaryProbeS3Client(remoteObject),
                            metadataDao = BoundaryProbeS3MetadataDao(),
                            lifecycleRunner = runner,
                        )

                    val result = executor.performSync()

                    result.shouldBeInstanceOf<S3SyncResult.Error>()
                    runner.snapshotPaths.local shouldContain "note.md"
                    runner.snapshotPaths.remote shouldContain "note.md"
                    runner.snapshotPaths.metadata shouldBe emptySet()
                    withClue("plan stage must not materialize final conflicts") {
                        runner.planConflictMaterialized shouldBe false
                    }
                    withClue("verify stage must not materialize final conflicts") {
                        runner.verifiedConflictMaterialized shouldBe false
                    }
                    runner.materializedConflictCount shouldBe 1
                } finally {
                    tempFolder.cleanup()
                }
            }
        }
    }
}

private class BoundaryProbeLifecycleRunner : RemoteSyncLifecycleRunner {
    var snapshotPaths: StagePaths = StagePaths()
        private set
    var planConflictMaterialized: Boolean = false
        private set
    var verifiedConflictMaterialized: Boolean = false
        private set
    var materializedConflictCount: Int = 0
        private set

    override suspend fun <TSnapshot, TPlan, TVerified, TConflicts, TApplied, TMetadata, TFinalized, TResult> run(
        stages: RemoteSyncLifecycleStages<TSnapshot, TPlan, TVerified, TConflicts, TApplied, TMetadata, TFinalized, TResult>,
    ): TResult {
        val session = TestSyncLifecycleExecutionOwner().begin(stages.context)
        val snapshot = stages.loadSnapshot(session)
        snapshotPaths = stagePaths(snapshot as Any)
        val plan = stages.plan(snapshot, session)
        planConflictMaterialized = hasConflictOrReviewMaterialized(plan as Any)
        val verified = stages.verify(plan, session)
        verifiedConflictMaterialized = hasConflictOrReviewMaterialized(verified as Any)
        val conflicts = stages.materializeConflicts(verified, session)
        materializedConflictCount = materializedConflictCount(conflicts as Any)
        return stages.mapError(IllegalStateException("stage boundary probe completed"))
    }
}

private data class StagePaths(
    val local: Set<String> = emptySet(),
    val remote: Set<String> = emptySet(),
    val metadata: Set<String> = emptySet(),
)

private fun stagePaths(stagePayload: Any): StagePaths =
    StagePaths(
        local = pathKeys(stagePayload, "localFiles"),
        remote = pathKeys(stagePayload, "remoteFiles"),
        metadata = pathKeys(stagePayload, "metadataByPath"),
    )

private fun hasConflictOrReviewMaterialized(stagePayload: Any): Boolean {
    val prepared = stagePayload.fieldValue("prepared") ?: stagePayload
    return prepared.fieldValue("conflictSet") != null || prepared.fieldValue("reviewSession") != null
}

private fun materializedConflictCount(stagePayload: Any): Int {
    stagePayload.fieldValue("conflict")?.let { materialized ->
        return materializedConflictCount(materialized)
    }
    stagePayload.fieldValue("review")?.let { materialized ->
        return materializedConflictCount(materialized)
    }
    val conflictSet = stagePayload.fieldValue("conflictSet") as? SyncConflictSet
    val reviewSession = stagePayload.fieldValue("reviewSession") as? SyncReviewSession
    return conflictSet?.files?.size ?: reviewSession?.items?.size ?: 0
}

private fun pathKeys(
    stagePayload: Any,
    fieldName: String,
): Set<String> =
    (stagePayload.fieldValue(fieldName) as? Map<*, *>)
        ?.keys
        ?.mapNotNull { key -> key as? String }
        ?.toSet()
        ?: emptySet()

private fun Any.fieldValue(fieldName: String): Any? {
    val field =
        generateSequence(javaClass as Class<*>?) { type -> type.superclass }
            .mapNotNull { type -> type.declaredFields.firstOrNull { field -> field.name == fieldName } }
            .firstOrNull()
            ?: return null
    field.isAccessible = true
    return field.get(this)
}

private fun createBoundaryExecutor(
    localRoot: File,
    client: LomoS3Client,
    metadataDao: S3SyncMetadataDao,
    lifecycleRunner: RemoteSyncLifecycleRunner,
): S3SyncExecutor {
    val dataStore = configuredBoundaryDataStore(localRoot)
    val credentialStore = mockk<S3CredentialStore>()
    every { credentialStore.getSecret(CredentialField.S3_ACCESS_KEY_ID) } returns "access"
    every { credentialStore.getSecret(CredentialField.S3_SECRET_ACCESS_KEY) } returns "secret"
    every { credentialStore.getSecret(CredentialField.S3_SESSION_TOKEN) } returns null
    every { credentialStore.getSecret(CredentialField.S3_ENCRYPTION_PASSWORD) } returns null
    every { credentialStore.getSecret(CredentialField.S3_ENCRYPTION_PASSWORD2) } returns null
    val runtime =
        S3SyncRepositoryContext(
            dataStore = dataStore,
            credentialStore = credentialStore,
            clientFactory = LomoS3ClientFactory { client },
            markdownStorageDataSource = mockk<MarkdownStorageDataSource>(),
            localMediaSyncStore = mockk<LocalMediaSyncStore>(),
            metadataDao = metadataDao,
            memoSynchronizer = mockk<MemoSynchronizer>(),
            planner = S3SyncPlanner(timestampToleranceMs = 0L),
            performanceTuner = DisabledSyncPerformanceTuner,
            stateHolder = S3SyncStateHolder(),
            transactionRunner = NoOpS3SyncTransactionRunner,
        )
    val encodingSupport = S3SyncEncodingSupport()
    val fileBridge = S3SyncFileBridge(runtime, encodingSupport)
    return S3SyncExecutor(
        runtime = runtime,
        support = S3SyncRepositorySupport(
                runtime = runtime,
                credentialRepository = testS3CredentialRepository(),
                securitySessionPolicy = AuthorizedCredentialReadSessionPolicy,
        ),
        encodingSupport = encodingSupport,
        objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
        fileBridge = fileBridge,
        actionApplier =
            S3SyncActionApplier(
                runtime = runtime,
                encodingSupport = encodingSupport,
                objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
                fileBridge = fileBridge,
                transferWorkspace = S3SyncTransferWorkspace.systemTemp(),
            ),
        lifecycleRunner = lifecycleRunner,
        protocolStateStore = DisabledS3SyncProtocolStateStore,
        localChangeJournalStore = DisabledS3LocalChangeJournalStore,
        remoteIndexStore = DisabledS3RemoteIndexStore,
        remoteShardStateStore = DisabledS3RemoteShardStateStore,
        pendingConflictStore = InMemoryPendingSyncConflictStore(),
        pendingReviewStore = InMemoryPendingSyncReviewStore(),
    )
}

private fun configuredBoundaryDataStore(localRoot: File): LomoDataStore =
    mockk<LomoDataStore>().also { dataStore ->
        every { dataStore.s3SyncEnabled } returns flowOf(true)
        every { dataStore.s3EndpointUrl } returns flowOf("https://s3.example.com")
        every { dataStore.s3Region } returns flowOf("us-east-1")
        every { dataStore.s3Bucket } returns flowOf("bucket")
        every { dataStore.s3Prefix } returns flowOf("")
        every { dataStore.s3LocalSyncDirectory } returns flowOf(localRoot.absolutePath)
        every { dataStore.s3PathStyle } returns flowOf("path_style")
        every { dataStore.s3EncryptionMode } returns flowOf("none")
        every { dataStore.s3RcloneFilenameEncryption } returns flowOf("standard")
        every { dataStore.s3RcloneFilenameEncoding } returns flowOf("base64")
        every { dataStore.s3RcloneDirectoryNameEncryption } returns flowOf(true)
        every { dataStore.s3RcloneDataEncryptionEnabled } returns flowOf(true)
        every { dataStore.s3RcloneEncryptedSuffix } returns flowOf(".bin")
        every { dataStore.rootDirectory } returns flowOf(localRoot.absolutePath)
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf(File(localRoot, "images").absolutePath)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(File(localRoot, "voice").absolutePath)
        every { dataStore.voiceUri } returns flowOf(null)
    }

private class BoundaryProbeS3Client(
    private val remoteObject: S3RemoteObject,
) : LomoS3Client {
    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = listOf(remoteObject).filter { objectSummary -> objectSummary.key.startsWith(prefix) }

    override suspend fun listPage(
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): S3RemoteListPage =
        S3RemoteListPage(
            objects =
                if (continuationToken == null && remoteObject.key.startsWith(prefix)) {
                    listOf(remoteObject)
                } else {
                    emptyList()
                },
            nextContinuationToken = null,
        )

    override suspend fun getObjectToFile(
        key: String,
        destination: File,
    ): S3RemoteObject = remoteObject

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload =
        S3SmallObjectPayload(
            key = key,
            eTag = remoteObject.eTag,
            lastModified = remoteObject.lastModified,
            metadata = remoteObject.metadata,
            bytes = "remote".toByteArray(),
        )

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult = S3PutObjectResult(eTag = "uploaded")

    override suspend fun putObjectFile(
        key: String,
        file: File,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult = S3PutObjectResult(eTag = "uploaded")

    override suspend fun deleteObject(key: String) = Unit

    override fun close() = Unit
}

private class BoundaryProbeS3MetadataDao : S3SyncMetadataDao {
    override suspend fun getAll(): List<S3SyncMetadataEntity> = emptyList()

    override suspend fun getAllPlannerMetadataSnapshots(): List<S3SyncPlannerMetadataSnapshot> = emptyList()

    override suspend fun getAllRemoteMetadataSnapshots(): List<S3SyncRemoteMetadataSnapshot> = emptyList()

    override suspend fun getByRelativePaths(relativePaths: List<String>): List<S3SyncMetadataEntity> = emptyList()

    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) = Unit

    override suspend fun deleteByRelativePath(relativePath: String) = Unit

    override suspend fun deleteByRelativePaths(relativePaths: List<String>) = Unit

    override suspend fun clearAll() = Unit
}
