package com.lomo.data.repository

import android.content.Context
import com.lomo.data.git.GitCredentialStore
import com.lomo.data.git.GitMediaSyncBridge
import com.lomo.data.git.GitMediaSyncSummary
import com.lomo.data.git.GitSyncEngine
import com.lomo.data.git.GitSyncQueryTestCoordinator
import com.lomo.data.git.SafGitMirrorBridge
import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.WebDavSyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.s3.S3DeleteObjectsResult
import com.lomo.data.s3.S3PutObjectResult
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3SmallObjectPayload
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.webdav.DefaultWebDavEndpointResolver
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.data.webdav.LocalMediaSyncFile
import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavClientFactory
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.data.webdav.WebDavRemoteResource
import com.lomo.data.webdav.WebDavSmallRemoteFile
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.WebDavSyncResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: WebDavSyncExecutor and S3SyncExecutor lifecycle routing.
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: provider executors must use the data-owned provider-neutral remote sync lifecycle runner.
 *
 * Scenarios:
 * - Given WebDAV sync is configured, when the executor runs, then it delegates the covered sync path to
 *   the injected lifecycle runner and returns the runner-mapped provider result.
 * - Given S3 sync is configured, when the executor runs, then it delegates the covered sync path to
 *   the injected lifecycle runner and returns the runner-mapped provider result.
 * - Given Git sync is configured, when the executor runs, then it delegates the sync path to the
 *   injected lifecycle runner and returns the runner-mapped provider result.
 * - Given S3/WebDAV pending conflict restore needs remote validation, when restore runs, then the
 *   injected lifecycle runner owns the restore path too.
 *
 * Observable outcomes:
 * - Runner invocation count and provider error result produced through the injected runner.
 *
 * TDD proof:
 * - RED: before the runner became a required executor dependency, this test fails to compile because
 *   WebDavSyncExecutor/S3SyncExecutor cannot receive a lifecycle runner and the old path can bypass it.
 *
 * Excludes:
 * - WebDAV/S3 transport behavior, planner correctness, metadata persistence details, and conflict contents.
 */

/*
 * Test Change Justification:
 * - Reason category: Signature update
 * - Old behavior/assertion being replaced: Fake S3 client collaborator putObjectFile and putSmallObject signatures without ifMatch and ifNoneMatch parameters.
 * - Why old assertion is no longer correct: The production S3 client interface has been upgraded with conditional write parameters for performance optimization.
 * - Coverage preserved by: All original test assertions are unchanged; the fake client is updated to compile against the new interface signature.
 * - Why this is not fitting the test to the implementation: This is a mechanical signature update to satisfy compile safety, not a change to the tested behavior.
 */
class RemoteSyncLifecycleProviderRoutingTest : DataFunSpec() {
    init {
        test("given configured webdav sync when executor runs then injected lifecycle runner owns the path") {
            runTest {
                val runner = RecordingRemoteSyncLifecycleRunner("webdav lifecycle runner invoked")
                val executor = createWebDavExecutor(runner)

                val result = executor.performSync()

                val error = result.shouldBeInstanceOf<WebDavSyncResult.Error>()
                error.message shouldBe "webdav lifecycle runner invoked"
                runner.runCalls shouldBe 1
            }
        }

        test("given configured s3 sync when executor runs then injected lifecycle runner owns the path") {
            runTest {
                val runner = RecordingRemoteSyncLifecycleRunner("s3 lifecycle runner invoked")
                val executor = createS3Executor(runner)

                val result = executor.performSync()

                val error = result.shouldBeInstanceOf<S3SyncResult.Error>()
                error.message shouldBe "S3 sync failed. Check endpoint, credentials, bucket access, and encryption settings."
                runner.runCalls shouldBe 1
            }
        }

        test("given configured git sync when executor runs then injected lifecycle runner owns the path") {
            runTest {
                val runner = RecordingRemoteSyncLifecycleRunner("git lifecycle runner invoked")
                val executor = createGitExecutor(runner)

                val result = executor.sync()

                val error = result.shouldBeInstanceOf<GitSyncResult.Error>()
                error.message shouldBe "git lifecycle runner invoked"
                runner.runCalls shouldBe 1
            }
        }

        test("given configured git sync when executor succeeds then lifecycle telemetry reports real repo status work") {
            runTest {
                val owner = TestSyncLifecycleExecutionOwner()
                val executor =
                    createGitExecutor(
                        lifecycleRunner = DefaultRemoteSyncLifecycleRunner(owner),
                        status = GitSyncStatus(hasLocalChanges = true, aheadCount = 2, behindCount = 3, lastSyncTime = null),
                        repoFiles = mapOf("note.md" to "memo", "media/photo.jpg" to "image"),
                    )

                val result = executor.sync()

                result shouldBe GitSyncResult.Success("sync ok")
                val report = owner.reports.singleOrNull().shouldNotBeNull()
                report.backend shouldBe SyncBackendType.GIT
                report.snapshot.localFileCount shouldBe 2
                report.snapshot.remoteFileCount shouldBe 0
                report.plannedActions.upload shouldBe 3
                report.plannedActions.download shouldBe 3
                report.plannedActions.total shouldBe 6
                report.verifiedActions shouldBe report.plannedActions
                report.result shouldBe RemoteSyncLifecycleResultTelemetry.Success
            }
        }

        test("given s3 pending restore when executor runs then injected lifecycle runner owns the path") {
            runTest {
                val runner = RecordingRemoteSyncLifecycleRunner("s3 pending restore lifecycle runner invoked")
                val executor = createS3Executor(runner)

                val result = executor.restorePendingConflict(pendingDescriptor(SyncBackendType.S3))

                val failed = result.shouldBeInstanceOf<PendingSyncRestoreResult.Failed>()
                failed.error.category shouldBe PendingSyncRestoreErrorCategory.CONTRACT_VIOLATION
                runner.runCalls shouldBe 1
            }
        }

        test("given webdav pending restore when executor runs then injected lifecycle runner owns the path") {
            runTest {
                val runner = RecordingRemoteSyncLifecycleRunner("webdav pending restore lifecycle runner invoked")
                val executor = createWebDavExecutor(runner)

                val result = executor.restorePendingConflict(pendingDescriptor(SyncBackendType.WEBDAV))

                val failed = result.shouldBeInstanceOf<PendingSyncRestoreResult.Failed>()
                failed.error.category shouldBe PendingSyncRestoreErrorCategory.CONTRACT_VIOLATION
                runner.runCalls shouldBe 1
            }
        }

        test("given s3 pending restore validates remote descriptor when restored then network head and get are metered") {
            runTest {
                val owner = TestSyncLifecycleExecutionOwner()
                val client = DescriptorRestoreS3Client()
                val rootDir = Files.createTempDirectory("lomo-s3-pending-restore").toFile()
                val localFile = File(rootDir, "note.md")
                localFile.writeText("local")
                localFile.setLastModified(1L)
                val executor =
                    createS3Executor(
                        lifecycleRunner = DefaultRemoteSyncLifecycleRunner(owner),
                        clientFactory = LomoS3ClientFactory { client },
                        rootDirectory = rootDir.absolutePath,
                    )

                val result =
                    executor.restorePendingConflict(
                        pendingDescriptor(
                            source = SyncBackendType.S3,
                            relativePath = "note.md",
                            localContent = "local",
                            remoteContent = "remote",
                            localLastModified = localFile.lastModified(),
                            remoteLastModified = 2L,
                        ),
                    )

                result.shouldBeInstanceOf<PendingSyncRestoreResult.Restored<*>>()
                val report = owner.reports.singleOrNull().shouldNotBeNull()
                report.backend shouldBe SyncBackendType.S3
                report.network.head shouldBe 1
                report.network.get shouldBe 1
                report.budget.consumedNetworkOperations shouldBe 2
                report.budget.remainingNetworkOperations shouldBe DEFAULT_REMOTE_SYNC_NETWORK_OPERATION_BUDGET - 2
            }
        }

        test("given webdav pending restore validates remote descriptor when restored then network list and get are metered") {
            runTest {
                val owner = TestSyncLifecycleExecutionOwner()
                val client = DescriptorRestoreWebDavClient()
                val markdownStorageDataSource =
                    mockk<MarkdownStorageDataSource>().also { storage ->
                        coEvery { storage.listMetadataIn(MemoDirectoryType.MAIN) } returns
                            listOf(FileMetadata(filename = "note.md", lastModified = 1L, size = 5L))
                        coEvery { storage.readFileIn(MemoDirectoryType.MAIN, "note.md") } returns "local"
                    }
                val executor =
                    createWebDavExecutor(
                        lifecycleRunner = DefaultRemoteSyncLifecycleRunner(owner),
                        clientFactory = WebDavClientFactory { _, _, _ -> client },
                        markdownStorageDataSource = markdownStorageDataSource,
                    )

                val result =
                    executor.restorePendingConflict(
                        pendingDescriptor(
                            source = SyncBackendType.WEBDAV,
                            relativePath = "lomo/memo/note.md",
                            localContent = "local",
                            remoteContent = "remote",
                            localLastModified = 1L,
                            remoteLastModified = 2L,
                        ),
                    )

                result.shouldBeInstanceOf<PendingSyncRestoreResult.Restored<*>>()
                val report = owner.reports.singleOrNull().shouldNotBeNull()
                report.backend shouldBe SyncBackendType.WEBDAV
                report.network.list shouldBe 1
                report.network.get shouldBe 1
                report.budget.consumedNetworkOperations shouldBe 2
                report.budget.remainingNetworkOperations shouldBe DEFAULT_REMOTE_SYNC_NETWORK_OPERATION_BUDGET - 2
            }
        }
    }
}

private class RecordingRemoteSyncLifecycleRunner(
    private val marker: String,
) : RemoteSyncLifecycleRunner {
    var runCalls: Int = 0
        private set

    override suspend fun <TSnapshot, TPlan, TVerified, TConflicts, TApplied, TMetadata, TFinalized, TResult> run(
        stages: RemoteSyncLifecycleStages<TSnapshot, TPlan, TVerified, TConflicts, TApplied, TMetadata, TFinalized, TResult>,
    ): TResult {
        runCalls += 1
        return stages.mapError(IllegalStateException(marker))
    }
}

private fun createWebDavExecutor(
    lifecycleRunner: RemoteSyncLifecycleRunner,
    clientFactory: WebDavClientFactory? = null,
    markdownStorageDataSource: MarkdownStorageDataSource = mockk<MarkdownStorageDataSource>(),
): WebDavSyncExecutor {
    val dataStore = configuredRemoteSyncDataStore()
    every { dataStore.webDavSyncEnabled } returns flowOf(true)
    every { dataStore.webDavProvider } returns flowOf("custom")
    every { dataStore.webDavBaseUrl } returns flowOf(null)
    every { dataStore.webDavEndpointUrl } returns flowOf("https://dav.example.com/root/")
    every { dataStore.webDavUsername } returns flowOf("alice")

    val credentialStore = mockk<WebDavCredentialStore>()
    every { credentialStore.getPassword() } returns "secret"

    val resolvedClientFactory =
        clientFactory ?: mockk<WebDavClientFactory>().also { factory ->
            every { factory.create("https://dav.example.com/root/", "alice", "secret") } returns mockk<WebDavClient>()
        }

    val localMediaSyncStore =
        mockk<LocalMediaSyncStore>().also { store ->
            coEvery { store.listFiles(any()) } returns emptyMap<String, LocalMediaSyncFile>()
        }
    val runtime =
        WebDavSyncRepositoryContext(
            dataStore = dataStore,
            credentialStore = credentialStore,
            endpointResolver = DefaultWebDavEndpointResolver(),
            clientFactory = resolvedClientFactory,
            markdownStorageDataSource = markdownStorageDataSource,
            localMediaSyncStore = localMediaSyncStore,
            metadataDao = mockk<WebDavSyncMetadataDao>(),
            memoSynchronizer = mockk<MemoSynchronizer>(),
            planner = WebDavSyncPlanner(timestampToleranceMs = 0L),
            performanceTuner = DisabledSyncPerformanceTuner,
            stateHolder = WebDavSyncStateHolder(),
        )
    val fileBridge =
        WebDavSyncFileBridge(
            runtime = runtime,
            localFingerprintCache = TestInMemoryWebDavLocalFingerprintCache(),
            remoteListingCache = WebDavRemoteListingCache(),
        )
    return WebDavSyncExecutor(
        runtime = runtime,
        support = WebDavSyncRepositorySupport(
                runtime = runtime,
                credentialRepository = testWebDavCredentialRepository(),
                securitySessionPolicy = AuthorizedCredentialReadSessionPolicy,
            ),
        fileBridge = fileBridge,
        actionApplier = WebDavSyncActionApplier(runtime, fileBridge),
        lifecycleRunner = lifecycleRunner,
        localChangeJournalStore = TestDisabledWebDavLocalChangeJournalStore,
        pendingConflictStore = InMemoryPendingSyncConflictStore(),
        pendingReviewStore = InMemoryPendingSyncReviewStore(),
    )
}

private fun createS3Executor(
    lifecycleRunner: RemoteSyncLifecycleRunner,
    clientFactory: LomoS3ClientFactory = LomoS3ClientFactory { mockk(relaxed = true) },
    rootDirectory: String? = null,
): S3SyncExecutor {
    val dataStore = configuredRemoteSyncDataStore()
    every { dataStore.s3SyncEnabled } returns flowOf(true)
    every { dataStore.s3EndpointUrl } returns flowOf("https://s3.example.com")
    every { dataStore.s3Region } returns flowOf("us-east-1")
    every { dataStore.s3Bucket } returns flowOf("bucket")
    every { dataStore.s3Prefix } returns flowOf("")
    every { dataStore.s3LocalSyncDirectory } returns flowOf(rootDirectory)
    every { dataStore.s3PathStyle } returns flowOf("path_style")
    every { dataStore.s3EncryptionMode } returns flowOf("none")
    every { dataStore.s3RcloneFilenameEncryption } returns flowOf("standard")
    every { dataStore.s3RcloneFilenameEncoding } returns flowOf("base64")
    every { dataStore.s3RcloneDirectoryNameEncryption } returns flowOf(true)
    every { dataStore.s3RcloneDataEncryptionEnabled } returns flowOf(true)
    every { dataStore.s3RcloneEncryptedSuffix } returns flowOf(".bin")

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
            clientFactory = clientFactory,
            markdownStorageDataSource = mockk<MarkdownStorageDataSource>(),
            localMediaSyncStore = mockk<LocalMediaSyncStore>(),
            metadataDao = mockk<S3SyncMetadataDao>(),
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

private fun createGitExecutor(
    lifecycleRunner: RemoteSyncLifecycleRunner,
    status: GitSyncStatus = GitSyncStatus(hasLocalChanges = false, aheadCount = 0, behindCount = 0, lastSyncTime = null),
    repoFiles: Map<String, String> = emptyMap(),
): GitSyncInitAndSyncExecutor {
    val rootDir = Files.createTempDirectory("lomo-git-routing").toFile()
    File(rootDir, ".git").mkdirs()
    repoFiles.forEach { (relativePath, content) ->
        File(rootDir, relativePath).also { file ->
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }
    val dataStore = configuredRemoteSyncDataStore()
    every { dataStore.gitSyncEnabled } returns flowOf(true)
    every { dataStore.gitRemoteUrl } returns flowOf("https://git.example.com/repo.git")
    every { dataStore.rootDirectory } returns flowOf(rootDir.absolutePath)
    every { dataStore.rootUri } returns flowOf(null)
    every { dataStore.imageDirectory } returns flowOf(rootDir.absolutePath)
    every { dataStore.imageUri } returns flowOf(null)
    every { dataStore.voiceDirectory } returns flowOf(rootDir.absolutePath)
    every { dataStore.voiceUri } returns flowOf(null)

    val gitSyncEngine = mockk<GitSyncEngine>()
    coEvery { gitSyncEngine.sync(any(), any()) } returns GitSyncResult.Success("sync ok")
    coEvery { gitSyncEngine.commitLocal(any()) } returns GitSyncResult.Success("commit ok")
    every { gitSyncEngine.markNotConfigured() } returns Unit
    every { gitSyncEngine.markError(any()) } returns Unit

    val credentialStore = mockk<GitCredentialStore>()
    coEvery { credentialStore.getToken() } returns "token"

    val memoSynchronizer = mockk<MemoSynchronizer>()
    coEvery { memoSynchronizer.refresh() } returns Unit

    val gitMediaSyncBridge = mockk<GitMediaSyncBridge>()
    coEvery { gitMediaSyncBridge.reconcile(any(), any()) } returns GitMediaSyncSummary(repoChanged = false)

    val runtime =
        GitSyncRepositoryContext(
            context = mockk<Context>().also { every { it.filesDir } returns rootDir },
            gitSyncEngine = gitSyncEngine,
            credentialStore = credentialStore,
            dataStore = dataStore,
            memoSynchronizer = memoSynchronizer,
            safGitMirrorBridge = mockk<SafGitMirrorBridge>(),
            gitMediaSyncBridge = gitMediaSyncBridge,
            gitSyncQueryCoordinator =
                mockk<GitSyncQueryTestCoordinator>().also { coordinator ->
                    every { coordinator.getStatus(rootDir) } returns status
                },
            markdownParser = mockk<MarkdownParser>(),
            markdownStorageDataSource = mockk<MarkdownStorageDataSource>(),
        )
    val memoMirror = mockk<GitSyncMemoMirror>()
    coEvery { memoMirror.mirrorMemoToRepo(any(), any()) } returns Unit
    coEvery { memoMirror.mirrorMemoFromRepo(any(), any()) } returns Unit
    return GitSyncInitAndSyncExecutor(
        runtime = runtime,
        support = GitSyncRepositorySupport(
                runtime = runtime,
                credentialRepository = testGitCredentialRepository(),
                securitySessionPolicy = AuthorizedCredentialReadSessionPolicy,
            ),
        memoMirror = memoMirror,
        lifecycleRunner = lifecycleRunner,
    )
}

private fun pendingDescriptor(
    source: SyncBackendType,
    relativePath: String = "lomo/memo/note.md",
    localContent: String = "local",
    remoteContent: String = "remote",
    localLastModified: Long = 1L,
    remoteLastModified: Long = 2L,
): PendingSyncConflictDescriptor =
    PendingSyncConflictDescriptor(
        source = source,
        workspaceGeneration = "test",
        files =
            listOf(
                PendingSyncConflictFileDescriptor(
                    relativePath = relativePath,
                    isBinary = false,
                    local =
                        PendingSyncSideMetadata(
                            locator = relativePath,
                            contentHash = localContent.toByteArray(Charsets.UTF_8).md5Hex(),
                            lastModified = localLastModified,
                            size = localContent.toByteArray(Charsets.UTF_8).size.toLong(),
                            etag = localContent.toByteArray(Charsets.UTF_8).md5Hex(),
                        ),
                    remote =
                        PendingSyncSideMetadata(
                            locator = relativePath,
                            contentHash = remoteContent.toByteArray(Charsets.UTF_8).md5Hex(),
                            lastModified = remoteLastModified,
                            size = remoteContent.toByteArray(Charsets.UTF_8).size.toLong(),
                            etag = remoteContent.toByteArray(Charsets.UTF_8).md5Hex(),
                        ),
                ),
            ),
        timestamp = 1L,
        validationStatus = PendingSyncValidationStatus.PENDING_RELOAD,
    )

private class DescriptorRestoreS3Client : LomoS3Client {
    private val remoteBytes = "remote".toByteArray(Charsets.UTF_8)
    private val remoteEtag = remoteBytes.md5Hex()

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun getObjectMetadata(key: String): S3RemoteObject =
        S3RemoteObject(
            key = key,
            eTag = remoteEtag,
            lastModified = 2L,
            size = remoteBytes.size.toLong(),
            metadata = emptyMap(),
        )

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = emptyList()

    override suspend fun getObjectToFile(
        key: String,
        destination: File,
    ): S3RemoteObject = getObjectMetadata(key)

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload =
        S3SmallObjectPayload(
            key = key,
            eTag = remoteEtag,
            lastModified = 2L,
            metadata = emptyMap(),
            bytes = remoteBytes,
        )

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult = S3PutObjectResult(eTag = bytes.md5Hex())

    override suspend fun putObjectFile(
        key: String,
        file: File,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult = S3PutObjectResult(eTag = file.readBytes().md5Hex())

    override suspend fun deleteObject(key: String) = Unit

    override suspend fun deleteObjects(keys: List<String>): S3DeleteObjectsResult = S3DeleteObjectsResult()

    override fun close() = Unit
}

private class DescriptorRestoreWebDavClient : WebDavClient {
    private val remoteBytes = "remote".toByteArray(Charsets.UTF_8)
    private val remoteEtag = remoteBytes.md5Hex()

    override fun ensureDirectory(path: String) = Unit

    override fun list(path: String): List<WebDavRemoteResource> =
        listOf(
            WebDavRemoteResource(
                path = "lomo/memo/note.md",
                isDirectory = false,
                etag = remoteEtag,
                lastModified = 2L,
                size = remoteBytes.size.toLong(),
            ),
        )

    override fun getToFile(
        path: String,
        destination: File,
    ): WebDavRemoteResource {
        destination.writeBytes(remoteBytes)
        return list(path).single()
    }

    override fun getSmallFile(path: String): WebDavSmallRemoteFile =
        WebDavSmallRemoteFile(
            path = path,
            bytes = remoteBytes,
            etag = remoteEtag,
            lastModified = 2L,
        )

    override fun putSmallFile(
        path: String,
        bytes: ByteArray,
        contentType: String,
        lastModifiedHint: Long?,
        expectedEtag: String?,
        requireAbsent: Boolean,
    ) = Unit

    override fun putFile(
        path: String,
        file: File,
        contentType: String,
        lastModifiedHint: Long?,
        expectedEtag: String?,
        requireAbsent: Boolean,
    ) = Unit

    override fun delete(
        path: String,
        expectedEtag: String?,
    ) = Unit

    override fun testConnection() = Unit
}

private fun configuredRemoteSyncDataStore(): LomoDataStore =
    mockk<LomoDataStore>().also { dataStore ->
        every { dataStore.rootDirectory } returns flowOf("/tmp/lomo/memo")
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf("/tmp/lomo/images")
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf("/tmp/lomo/voice")
        every { dataStore.voiceUri } returns flowOf(null)
    }
