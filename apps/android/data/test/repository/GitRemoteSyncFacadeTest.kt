package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: GitRemoteSyncFacade (production post-cutover facade)
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: sync() returns Accepted (WorkManager admission) or a typed rejection — never
 *   fabricated Success; getStatus projects the durable cycle_state.rec record; testConnection
 *   runs a real probe round-trip, not an enqueue receipt.
 *
 * Scenarios:
 * - Given no active backend, when sync runs, then NotConfigured (rejected enqueue is not success).
 * - Given no Direct workspace root, when sync runs, then DirectPathRequired.
 * - Given a durable completed record, when getStatus runs, then counts/timestamp come from the
 *   record (not zeros).
 * - Given no durable record, when getStatus runs, then honest zero counts and null lastSyncTime.
 * - Given blank remote config, when testConnection runs, then NotConfigured without a probe call.
 * - Given resetRepository, when invoked, then the Rust control tree is cleared and Success reports it.
 *
 * Observable outcomes: GitSyncResult variants, GitSyncStatus fields, fake repository call records.
 * TDD proof:
 * - Fails before the fix because the facade surface under test does not exist.
 * Excludes: real WorkManager admission (instrumented), JNI (native contract tests own it).
 */

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.sync.RemoteSyncBackendProbe
import com.lomo.data.engine.sync.RemoteSyncBoundaryFailure
import com.lomo.data.engine.sync.RemoteSyncConflictPage
import com.lomo.data.engine.sync.RemoteSyncConflictResolveResult
import com.lomo.data.engine.sync.RemoteSyncConflictResolution
import com.lomo.data.engine.sync.RemoteSyncCyclePlanSummary
import com.lomo.data.engine.sync.RemoteSyncCycleRequest
import com.lomo.data.engine.sync.RemoteSyncCycleStatus
import com.lomo.data.engine.sync.RemoteSyncRepository
import com.lomo.data.engine.sync.RemoteSyncSecretLease
import com.lomo.data.engine.sync.RustSyncCycleStatusStore
import com.lomo.data.engine.sync.RustSyncSecretSupplier
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.MemorySecretMaterialSource
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.repository.GitSyncConfigurationMutationRepository
import com.lomo.domain.repository.GitSyncConfigurationRepository
import com.lomo.domain.repository.GitSyncStateRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class GitRemoteSyncFacadeTest : DataFunSpec() {
    init {
        test("sync with no active backend returns NotConfigured not success") {
            runTest {
                val harness = FacadeHarness(backend = "none")

                harness.facade.sync() shouldBe GitSyncResult.NotConfigured
            }
        }

        test("sync without a Direct workspace root returns DirectPathRequired") {
            runTest {
                val harness =
                    FacadeHarness(backend = "git", workspaceRoot = null)

                harness.facade.sync() shouldBe GitSyncResult.DirectPathRequired
            }
        }

        test("getStatus projects the durable cycle record not fabricated zeros") {
            runTest {
                val harness = FacadeHarness(backend = "git")
                harness.remoteSync.nextCycleStatus =
                    durableStatus().copy(
                        phase = RemoteSyncCycleStatus.PHASE_COMPLETED,
                        ensurePresentCount = 3,
                        ensureAbsentCount = 1,
                        pullPresentCount = 2,
                        lastSuccessfulAtMs = 1_700_000_000_000L,
                    )

                val status = harness.facade.getStatus()

                status.hasLocalChanges shouldBe true
                status.aheadCount shouldBe 4
                status.behindCount shouldBe 2
                status.lastSyncTime shouldBe 1_700_000_000_000L
                harness.remoteSync.lastCycleStatusRoot shouldBe "/workspaces/notes"
            }
        }

        test("getStatus without a durable record reports honest zeros and null lastSyncTime") {
            runTest {
                val harness = FacadeHarness(backend = "git")

                val status = harness.facade.getStatus()

                status.hasLocalChanges shouldBe false
                status.aheadCount shouldBe 0
                status.behindCount shouldBe 0
                status.lastSyncTime shouldBe null
            }
        }

        test("testConnection with blank remote config returns NotConfigured without probing") {
            runTest {
                val harness = FacadeHarness(backend = "git")

                harness.facade.testConnection() shouldBe GitSyncResult.NotConfigured
                harness.remoteSync.probeCallCount shouldBe 0
            }
        }

        test("testConnection runs a real backend probe with persisted config") {
            runTest {
                val harness = FacadeHarness(backend = "git")
                harness.configuration.gitRemoteUrl.value = "https://example.com/repo.git"
                harness.remoteSync.nextProbe =
                    RemoteSyncBackendProbe(
                        backendKind = "git",
                        listedEntryCount = 7,
                        snapshotRevisionPresent = true,
                        conditionalWrite = true,
                        conditionalDelete = true,
                        probedAtMs = 5L,
                    )

                val result = harness.facade.testConnection()

                result.shouldBeInstanceOf<GitSyncResult.Success>()
                result.message shouldBe "connected: 7 remote entries (snapshot revision present)"
                harness.remoteSync.probeCallCount shouldBe 1
                harness.remoteSync.lastProbeRequest?.backendKind shouldBe "git"
            }
        }

        test("resetRepository clears the Rust control tree for the direct root") {
            runTest {
                val harness = FacadeHarness(backend = "git")

                val result = harness.facade.resetRepository()

                result.shouldBeInstanceOf<GitSyncResult.Success>()
                harness.remoteSync.lastResetRoot shouldBe "/workspaces/notes"
            }
        }
    }
}

private class FacadeHarness(
    backend: String,
    workspaceRoot: String? = "/workspaces/notes",
) {
    // DataStore writes run on a real dispatcher — the virtual test scheduler would deadlock
    // the harness's synchronous init write.
    private val storeScope =
        CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    val dataStore: LomoDataStore = createLomoDataStore(storeScope)
    val remoteSync = FakeFacadeRemoteSync()
    val configuration = FakeGitConfiguration()
    val root = WorkspaceFilesystemRoot { workspaceRoot }

    private val scheduler =
        RustSyncScheduler(
            context = mockk<Context>(),
            dataStore = dataStore,
            workspaceRoot = root,
            identityMaterial = MemorySecretMaterialSource(),
        )

    val facade: GitRemoteSyncFacade =
        GitRemoteSyncFacade(
            configuration = configuration,
            configurationMutation = FakeGitConfigurationMutation(),
            state = FakeGitStateRepository(),
            rustSyncScheduler = scheduler,
            remoteSync = remoteSync,
            secretSupplier = FakeFacadeSecretSupplier(),
            cycleStatus = RustSyncCycleStatusStore(remoteSync, root),
        )

    init {
        if (backend != "none") {
            runBlockingStore {
                dataStore.setRemoteSyncBackendType(backend)
            }
        }
    }
}

private fun runBlockingStore(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}

private class FakeGitConfiguration : GitSyncConfigurationRepository {
    val gitRemoteUrl = MutableStateFlow<String?>(null)
    private val enabled = MutableStateFlow(true)

    override fun isGitSyncEnabled(): Flow<Boolean> = enabled

    override fun getRemoteUrl(): Flow<String?> = gitRemoteUrl

    override fun getBranch(): Flow<String> = flowOf("main")

    override fun getAutoSyncEnabled(): Flow<Boolean> = flowOf(false)

    override fun getAutoSyncInterval(): Flow<String> = flowOf("1h")

    override fun observeLastSyncTimeMillis(): Flow<Long?> = flowOf(null)

    override fun getSyncOnRefreshEnabled(): Flow<Boolean> = flowOf(false)
}

private class FakeGitConfigurationMutation : GitSyncConfigurationMutationRepository {
    override suspend fun setRemoteUrl(url: String) = Unit

    override suspend fun setBranch(branch: String) = Unit

    override suspend fun setToken(token: String) = Unit

    override suspend fun getTokenStatus(): StoredCredentialStatus = StoredCredentialStatus.Missing

    override suspend fun setAuthorInfo(
        name: String,
        email: String,
    ) = Unit

    override fun getAuthorName(): Flow<String> = flowOf("Lomo")

    override fun getAuthorEmail(): Flow<String> = flowOf("git@lomo.local")

    override suspend fun setAutoSyncEnabled(enabled: Boolean) = Unit

    override suspend fun setAutoSyncInterval(interval: String) = Unit

    override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) = Unit
}

private class FakeGitStateRepository : GitSyncStateRepository {
    override fun syncState(): Flow<UnifiedSyncState> = flowOf(UnifiedSyncState.Idle)
}

private class FakeFacadeSecretSupplier : RustSyncSecretSupplier {
    override fun issueLease(
        fieldKey: String,
        ttlMillis: Long,
    ): RemoteSyncSecretLease = RemoteSyncSecretLease(leaseId = "lease-$fieldKey")

    override fun revokeLease(leaseId: String) = Unit

    override fun identityUtf8(fieldKey: String): String? = null
}

private class FakeFacadeRemoteSync : RemoteSyncRepository {
    var nextCycleStatus: RemoteSyncCycleStatus = durableStatus().copy(hasRecord = false)
    var lastCycleStatusRoot: String? = null
    var probeCallCount = 0
    var lastProbeRequest: RemoteSyncCycleRequest? = null
    var nextProbe: RemoteSyncBackendProbe =
        RemoteSyncBackendProbe(
            backendKind = "git",
            listedEntryCount = 0,
            snapshotRevisionPresent = false,
            conditionalWrite = false,
            conditionalDelete = false,
            probedAtMs = 0L,
        )
    var lastResetRoot: String? = null

    override fun cycleStatus(workspaceRoot: String): RemoteSyncCycleStatus {
        lastCycleStatusRoot = workspaceRoot
        return nextCycleStatus
    }

    override fun requestCancel(workspaceRoot: String): RemoteSyncCycleStatus = nextCycleStatus

    override fun probeBackend(request: RemoteSyncCycleRequest): RemoteSyncBackendProbe {
        probeCallCount += 1
        lastProbeRequest = request
        return nextProbe
    }

    override fun resetControlTree(workspaceRoot: String) {
        lastResetRoot = workspaceRoot
    }

    override fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage = error("unused")

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult = error("unused")

    override fun issueSecretLease(
        secretBytes: ByteArray,
        ttlMillis: Long,
    ): RemoteSyncSecretLease = error("unused")

    override fun probeSecretLease(leaseId: String): Int = error("unused")

    override fun revokeSecretLease(leaseId: String) = error("unused")

    override fun runCycle(request: RemoteSyncCycleRequest): RemoteSyncCyclePlanSummary = error("unused")

    override fun loadWorkspaceGeneration(workspaceRoot: String): String = error("unused")
}

private fun durableStatus(): RemoteSyncCycleStatus =
    RemoteSyncCycleStatus(
        hasRecord = true,
        cycleSeq = 3L,
        cycleId = "cycle-000003",
        fenceKey = "fence",
        backendKind = "git",
        sessionId = "session",
        applyRemote = true,
        phase = RemoteSyncCycleStatus.PHASE_COMPLETED,
        stage = RemoteSyncCycleStatus.STAGE_FINISHED,
        ensurePresentCount = 0,
        ensureAbsentCount = 0,
        pullPresentCount = 0,
        openConflictCount = 0,
        holdCount = 0,
        localEntryCount = 0,
        remoteListedCount = 0,
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
        lastSuccessfulAtMs = null,
        stateStamp = 4L,
    )

private fun createLomoDataStore(scope: CoroutineScope): LomoDataStore {
    val backingFile =
        Files.createTempFile("lomo-git-facade", ".preferences_pb").toFile().apply {
            deleteOnExit()
        }
    val realDataStore =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { backingFile },
        )
    val constructor =
        LomoDataStore::class.java.getDeclaredConstructor(DataStore::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(realDataStore)
}
