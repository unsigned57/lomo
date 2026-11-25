package com.lomo.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.sync.RemoteSyncBackendProbe
import com.lomo.data.engine.sync.RemoteSyncBoundaryFailure
import com.lomo.data.engine.sync.RemoteSyncConflictPage
import com.lomo.data.engine.sync.RemoteSyncConflictResolution
import com.lomo.data.engine.sync.RemoteSyncConflictResolveResult
import com.lomo.data.engine.sync.RemoteSyncCyclePlanSummary
import com.lomo.data.engine.sync.RemoteSyncCycleRequest
import com.lomo.data.engine.sync.RemoteSyncCycleStatus
import com.lomo.data.engine.sync.RemoteSyncRepository
import com.lomo.data.engine.sync.RemoteSyncSecretLease
import com.lomo.data.engine.sync.RustSyncCycleStatusStore
import com.lomo.data.engine.sync.RustSyncSecretSupplier
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.fakes.MemorySecretMaterialSource
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncErrorCode
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.repository.S3SyncConfigurationMutationRepository
import com.lomo.domain.repository.S3SyncConfigurationRepository
import com.lomo.domain.repository.S3SyncStateRepository
import com.lomo.domain.repository.WebDavSyncConfigurationMutationRepository
import com.lomo.domain.repository.WebDavSyncConfigurationRepository
import com.lomo.domain.repository.WebDavSyncStateRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: S3RemoteSyncFacade and WebDavRemoteSyncFacade
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: gate sync/testConnection on active direct root and required backend fields, project
 *   durable cycle_state.rec status cleanly, probe remote backends with bounded secret leases that
 *   are always revoked in finally, and map boundary failures honestly.
 * - Production scope: apps/android/data/src/repository/S3RemoteSyncFacade.kt
 * - Production scope: apps/android/data/src/repository/WebDavRemoteSyncFacade.kt
 * - Scenarios:
 *   - Given no active backend or missing direct workspace root, when S3 or WebDAV sync runs, then
 *     NotConfigured or Error is returned without fabricated Success.
 *   - Given a durable cycle record vs no record, when S3 or WebDAV getStatus runs, then counts and
 *     lastSuccessfulAtMs reflect the record or honest zeros/null.
 *   - Given missing required connection fields or missing direct root, when testConnection runs,
 *     then NotConfigured or Error returns without invoking probeBackend.
 *   - Given complete S3 or WebDAV (including baseUrl fallback) configuration, when testConnection
 *     succeeds or throws RemoteSyncBoundaryFailure, then Success or Error returns and the issued
 *     secret lease is revoked.
 * - Observable outcomes: S3SyncResult / WebDavSyncResult, S3SyncStatus / WebDavSyncStatus,
 *   captured probe requests, and secret lease issue/revoke records.
 * - TDD proof: locks S3 and WebDAV remote sync facades that previously had zero host test coverage.
 * - Excludes: WorkManager enqueue internals and real Rust JNI transport.
 */
class RemoteSyncFacadesTest :
    FunSpec({
        fun newHarness(backend: String, workspaceRoot: String? = "/workspaces/notes"): FacadeTestHarness =
            FacadeTestHarness(backend = backend, workspaceRoot = workspaceRoot)

        test("given missing backend or direct root when s3 or webdav sync runs then rejected honestly") {
            runTest {
                newHarness(backend = "none").s3Facade.sync() shouldBe S3SyncResult.NotConfigured
                val noRootS3 = newHarness(backend = "s3", workspaceRoot = null).s3Facade.sync()
                noRootS3.shouldBeInstanceOf<S3SyncResult.Error>().code shouldBe S3SyncErrorCode.UNKNOWN

                newHarness(backend = "none").webDavFacade.sync() shouldBe WebDavSyncResult.NotConfigured
                val noRootWebDav = newHarness(backend = "webdav", workspaceRoot = null).webDavFacade.sync()
                noRootWebDav.shouldBeInstanceOf<WebDavSyncResult.Error>().code shouldBe WebDavSyncErrorCode.UNKNOWN
            }
        }

        test("given durable vs absent cycle record when s3 or webdav getStatus runs then counts map accurately") {
            runTest {
                val harness = newHarness(backend = "s3")
                harness.remoteSync.nextCycleStatus =
                    durableCycleStatus(
                        remoteListedCount = 9,
                        localEntryCount = 4,
                        ensurePresent = 2,
                        ensureAbsent = 1,
                        pullPresent = 3,
                        lastSuccessMs = 1_725_000_000_000L,
                    )

                val s3Status = harness.s3Facade.getStatus()
                s3Status.remoteFileCount shouldBe 9
                s3Status.localFileCount shouldBe 4
                s3Status.pendingChanges shouldBe 6
                s3Status.lastSyncTime shouldBe 1_725_000_000_000L

                val webDavStatus = harness.webDavFacade.getStatus()
                webDavStatus.remoteFileCount shouldBe 9
                webDavStatus.localFileCount shouldBe 4
                webDavStatus.pendingChanges shouldBe 6
                webDavStatus.lastSyncTime shouldBe 1_725_000_000_000L

                val noRootS3 = newHarness(backend = "s3", workspaceRoot = null).s3Facade.getStatus()
                noRootS3.remoteFileCount shouldBe 0
                noRootS3.pendingChanges shouldBe 0
                noRootS3.lastSyncTime shouldBe null
            }
        }

        test("given incomplete config or probe success or failure when s3 testConnection runs then lease revoked") {
            runTest {
                val noRoot = newHarness(backend = "s3", workspaceRoot = null)
                noRoot.s3Facade.testConnection().shouldBeInstanceOf<S3SyncResult.Error>()

                val harness = newHarness(backend = "s3")
                harness.s3Facade.testConnection() shouldBe S3SyncResult.NotConfigured
                harness.remoteSync.probeCallCount shouldBe 0

                harness.s3Config.endpoint.value = "https://s3.example.com"
                harness.s3Config.region.value = "us-east-1"
                harness.s3Config.bucket.value = "notes-bucket"
                harness.s3Config.prefix.value = "vault/"
                harness.remoteSync.nextProbe =
                    RemoteSyncBackendProbe(
                        backendKind = "s3",
                        listedEntryCount = 12,
                        snapshotRevisionPresent = true,
                        conditionalWrite = true,
                        conditionalDelete = true,
                        probedAtMs = 10L,
                    )

                val ok = harness.s3Facade.testConnection().shouldBeInstanceOf<S3SyncResult.Success>()
                ok.message shouldBe "connected: 12 remote entries"
                harness.remoteSync.lastProbeRequest?.s3Prefix shouldBe "vault/"
                harness.secretSupplier.revokedLeases shouldBe listOf("lease-S3_SECRET_ACCESS_KEY")

                harness.remoteSync.probeFailure =
                    RemoteSyncBoundaryFailure(
                        category = "transport",
                        code = "s3_timeout",
                        retryDisposition = "transient",
                        diagnostic = "connect timed out",
                    )
                val err = harness.s3Facade.testConnection().shouldBeInstanceOf<S3SyncResult.Error>()
                err.message shouldBe "connect timed out"
                harness.secretSupplier.revokedLeases shouldBe
                    listOf("lease-S3_SECRET_ACCESS_KEY", "lease-S3_SECRET_ACCESS_KEY")
            }
        }

        test("given baseUrl fallback and probe outcomes when webdav testConnection runs then lease always revoked") {
            runTest {
                val harness = newHarness(backend = "webdav")
                harness.webDavFacade.testConnection() shouldBe WebDavSyncResult.NotConfigured

                harness.webDavConfig.endpoint.value = "   "
                harness.webDavConfig.baseUrl.value = "https://dav.example.com/remote.php/dav"
                harness.remoteSync.nextProbe =
                    RemoteSyncBackendProbe(
                        backendKind = "webdav",
                        listedEntryCount = 5,
                        snapshotRevisionPresent = false,
                        conditionalWrite = true,
                        conditionalDelete = false,
                        probedAtMs = 20L,
                    )

                val ok = harness.webDavFacade.testConnection().shouldBeInstanceOf<WebDavSyncResult.Success>()
                ok.message shouldBe "connected: 5 remote entries"
                harness.remoteSync.lastProbeRequest?.endpointUrl shouldBe "https://dav.example.com/remote.php/dav"
                harness.secretSupplier.revokedLeases shouldBe listOf("lease-WEBDAV_PASSWORD")

                harness.secretSupplier.issueFailure =
                    RemoteSyncBoundaryFailure(
                        category = "secret",
                        code = "locked",
                        retryDisposition = "after_user_action",
                        diagnostic = "",
                    )
                val err = harness.webDavFacade.testConnection().shouldBeInstanceOf<WebDavSyncResult.Error>()
                err.message shouldBe "locked"
            }
        }
    })

private class FacadeTestHarness(backend: String, workspaceRoot: String?) {
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore = createStore(storeScope)
    private val root = WorkspaceFilesystemRoot { workspaceRoot }
    val remoteSync = RecordingFacadeRemoteSync()
    val secretSupplier = RecordingFacadeSecretSupplier()
    val s3Config = FakeS3ConfigRepo()
    val webDavConfig = FakeWebDavConfigRepo()

    private val scheduler =
        RustSyncScheduler(
            context = mockk<Context>(),
            dataStore = dataStore,
            workspaceRoot = root,
            identityMaterial = MemorySecretMaterialSource(),
        )

    private val cycleStatus = RustSyncCycleStatusStore(remoteSync, root, storeScope)

    val s3Facade =
        S3RemoteSyncFacade(
            configuration = s3Config,
            configurationMutation = FakeS3MutationRepo(),
            state =
            object : S3SyncStateRepository {
                override fun syncState(): Flow<S3SyncState> = flowOf(S3SyncState.Idle)
            },
            rustSyncScheduler = scheduler,
            remoteSync = remoteSync,
            secretSupplier = secretSupplier,
            cycleStatus = cycleStatus,
        )

    val webDavFacade =
        WebDavRemoteSyncFacade(
            configuration = webDavConfig,
            configurationMutation = FakeWebDavMutationRepo(),
            state =
            object : WebDavSyncStateRepository {
                override fun syncState(): Flow<WebDavSyncState> = flowOf(WebDavSyncState.Idle)
            },
            rustSyncScheduler = scheduler,
            remoteSync = remoteSync,
            secretSupplier = secretSupplier,
            cycleStatus = cycleStatus,
        )

    init {
        if (backend != "none") {
            runBlocking { dataStore.setRemoteSyncBackendType(backend) }
        }
    }
}

private fun createStore(scope: CoroutineScope): LomoDataStore {
    val file = Files.createTempFile("lomo-remote-facade", ".preferences_pb").toFile().apply { deleteOnExit() }
    val prefs = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    val ctor = LomoDataStore::class.java.getDeclaredConstructor(DataStore::class.java)
    ctor.isAccessible = true
    return ctor.newInstance(prefs)
}

private class RecordingFacadeSecretSupplier : RustSyncSecretSupplier {
    val revokedLeases = mutableListOf<String>()
    var issueFailure: RemoteSyncBoundaryFailure? = null

    override fun issueLease(fieldKey: String, ttlMillis: Long): RemoteSyncSecretLease? {
        issueFailure?.let { throw it }
        return RemoteSyncSecretLease("lease-$fieldKey")
    }

    override fun revokeLease(leaseId: String) {
        revokedLeases += leaseId
    }

    override fun identityUtf8(fieldKey: String): String? = "user"
}

private class RecordingFacadeRemoteSync : RemoteSyncRepository {
    var nextCycleStatus = durableCycleStatus()
    var probeCallCount = 0
    var lastProbeRequest: RemoteSyncCycleRequest? = null
    var probeFailure: RemoteSyncBoundaryFailure? = null
    var nextProbe =
        RemoteSyncBackendProbe(
            backendKind = "s3",
            listedEntryCount = 0,
            snapshotRevisionPresent = false,
            conditionalWrite = false,
            conditionalDelete = false,
            probedAtMs = 0L,
        )

    override fun cycleStatus(workspaceRoot: String): RemoteSyncCycleStatus = nextCycleStatus

    override fun requestCancel(workspaceRoot: String): RemoteSyncCycleStatus = nextCycleStatus

    override fun probeBackend(request: RemoteSyncCycleRequest): RemoteSyncBackendProbe {
        probeCallCount += 1
        lastProbeRequest = request
        probeFailure?.let { throw it }
        return nextProbe
    }

    override fun resetControlTree(workspaceRoot: String) = Unit

    override fun listConflicts(workspaceRoot: String, cursor: Int, limit: Int): RemoteSyncConflictPage = error("unused")

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult = error("unused")

    override fun issueSecretLease(secretBytes: ByteArray, ttlMillis: Long): RemoteSyncSecretLease = error("unused")

    override fun probeSecretLease(leaseId: String): Int = error("unused")

    override fun revokeSecretLease(leaseId: String) = Unit

    override fun runCycle(request: RemoteSyncCycleRequest): RemoteSyncCyclePlanSummary = error("unused")

    override fun loadWorkspaceGeneration(workspaceRoot: String): String = error("unused")
}

private fun durableCycleStatus(
    remoteListedCount: Int = 0,
    localEntryCount: Int = 0,
    ensurePresent: Int = 0,
    ensureAbsent: Int = 0,
    pullPresent: Int = 0,
    lastSuccessMs: Long? = null,
): RemoteSyncCycleStatus = RemoteSyncCycleStatus(
    hasRecord = true,
    cycleSeq = 1L,
    cycleId = "cycle-1",
    fenceKey = "fence",
    backendKind = "s3",
    sessionId = "session",
    applyRemote = true,
    phase = RemoteSyncCycleStatus.PHASE_COMPLETED,
    stage = RemoteSyncCycleStatus.STAGE_FINISHED,
    ensurePresentCount = ensurePresent,
    ensureAbsentCount = ensureAbsent,
    pullPresentCount = pullPresent,
    openConflictCount = 0,
    holdCount = 0,
    localEntryCount = localEntryCount,
    remoteListedCount = remoteListedCount,
    baselineEntryCount = 0,
    pagesApplied = 0,
    baselineAdvanced = true,
    retryDisposition = "never",
    failureCode = null,
    failureMessage = null,
    cancelRequested = false,
    startedAtMs = 1L,
    updatedAtMs = 2L,
    finishedAtMs = 3L,
    lastSuccessfulAtMs = lastSuccessMs,
    stateStamp = 1L,
)

private class FakeS3ConfigRepo : S3SyncConfigurationRepository {
    val endpoint = MutableStateFlow<String?>(null)
    val region = MutableStateFlow<String?>(null)
    val bucket = MutableStateFlow<String?>(null)
    val prefix = MutableStateFlow<String?>(null)

    override fun isS3SyncEnabled(): Flow<Boolean> = flowOf(true)

    override fun getEndpointUrl(): Flow<String?> = endpoint

    override fun getRegion(): Flow<String?> = region

    override fun getBucket(): Flow<String?> = bucket

    override fun getPrefix(): Flow<String?> = prefix

    override fun getLocalSyncDirectory(): Flow<String?> = flowOf(null)

    override fun getPathStyle(): Flow<S3PathStyle> = flowOf(S3PathStyle.AUTO)

    override fun getEncryptionMode(): Flow<S3EncryptionMode> = flowOf(S3EncryptionMode.NONE)

    override fun getRcloneFilenameEncryption(): Flow<S3RcloneFilenameEncryption> =
        flowOf(S3RcloneFilenameEncryption.STANDARD)

    override fun getRcloneFilenameEncoding(): Flow<S3RcloneFilenameEncoding> = flowOf(S3RcloneFilenameEncoding.BASE64)

    override fun getRcloneDirectoryNameEncryption(): Flow<Boolean> = flowOf(true)

    override fun getRcloneDataEncryptionEnabled(): Flow<Boolean> = flowOf(true)

    override fun getRcloneEncryptedSuffix(): Flow<String> = flowOf(".bin")

    override fun getAutoSyncEnabled(): Flow<Boolean> = flowOf(false)

    override fun getAutoSyncInterval(): Flow<String> = flowOf("1h")

    override fun getSyncOnRefreshEnabled(): Flow<Boolean> = flowOf(false)

    override fun observeLastSyncTimeMillis(): Flow<Long?> = flowOf(null)
}

private class FakeS3MutationRepo : S3SyncConfigurationMutationRepository {
    override suspend fun setEndpointUrl(url: String) = Unit

    override suspend fun setRegion(region: String) = Unit

    override suspend fun setBucket(bucket: String) = Unit

    override suspend fun setPrefix(prefix: String) = Unit

    override suspend fun setLocalSyncDirectory(pathOrUri: String) = Unit

    override suspend fun clearLocalSyncDirectory() = Unit

    override suspend fun setPathStyle(pathStyle: S3PathStyle) = Unit

    override suspend fun setAccessKeyId(accessKeyId: String) = Unit

    override suspend fun setSecretAccessKey(secretAccessKey: String) = Unit

    override suspend fun setSessionToken(sessionToken: String) = Unit

    override suspend fun setEncryptionPassword(password: String) = Unit

    override suspend fun setEncryptionPassword2(password: String) = Unit

    override suspend fun isAccessKeyConfigured(): Boolean = false

    override suspend fun isSecretAccessKeyConfigured(): Boolean = false

    override suspend fun isSessionTokenConfigured(): Boolean = false

    override suspend fun isEncryptionPasswordConfigured(): Boolean = false

    override suspend fun isEncryptionPassword2Configured(): Boolean = false

    override suspend fun setEncryptionMode(mode: S3EncryptionMode) = Unit

    override suspend fun setRcloneFilenameEncryption(mode: S3RcloneFilenameEncryption) = Unit

    override suspend fun setRcloneFilenameEncoding(encoding: S3RcloneFilenameEncoding) = Unit

    override suspend fun setRcloneDirectoryNameEncryption(enabled: Boolean) = Unit

    override suspend fun setRcloneDataEncryptionEnabled(enabled: Boolean) = Unit

    override suspend fun setRcloneEncryptedSuffix(suffix: String) = Unit

    override suspend fun setAutoSyncEnabled(enabled: Boolean) = Unit

    override suspend fun setAutoSyncInterval(interval: String) = Unit

    override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) = Unit
}

private class FakeWebDavConfigRepo : WebDavSyncConfigurationRepository {
    val endpoint = MutableStateFlow<String?>(null)
    val baseUrl = MutableStateFlow<String?>(null)

    override fun isWebDavSyncEnabled(): Flow<Boolean> = flowOf(true)

    override fun getProvider(): Flow<WebDavProvider> = flowOf(WebDavProvider.CUSTOM)

    override fun getBaseUrl(): Flow<String?> = baseUrl

    override fun getEndpointUrl(): Flow<String?> = endpoint

    override fun getUsername(): Flow<String?> = flowOf(null)

    override fun getAutoSyncEnabled(): Flow<Boolean> = flowOf(false)

    override fun getAutoSyncInterval(): Flow<String> = flowOf("1h")

    override fun getSyncOnRefreshEnabled(): Flow<Boolean> = flowOf(false)

    override fun observeLastSyncTimeMillis(): Flow<Long?> = flowOf(null)
}

private class FakeWebDavMutationRepo : WebDavSyncConfigurationMutationRepository {
    override suspend fun setProvider(provider: WebDavProvider) = Unit

    override suspend fun setBaseUrl(url: String) = Unit

    override suspend fun setEndpointUrl(url: String) = Unit

    override suspend fun setUsername(username: String) = Unit

    override suspend fun setPassword(password: String) = Unit

    override suspend fun isPasswordConfigured(): Boolean = false

    override suspend fun setAutoSyncEnabled(enabled: Boolean) = Unit

    override suspend fun setAutoSyncInterval(interval: String) = Unit

    override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) = Unit
}
