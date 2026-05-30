package com.lomo.data.worker

import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.GitSyncWorkPolicyPlanner
import com.lomo.data.sync.SyncExistingWorkPolicy
import com.lomo.data.sync.SyncScheduledWork
import com.lomo.data.sync.SyncWorkCadence
import com.lomo.data.sync.SyncWorkNetworkRequirement
import com.lomo.data.sync.SyncWorkPayload
import com.lomo.data.sync.SyncWorkTrigger
import com.lomo.data.sync.WebDavSyncWorkPolicyPlanner
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.SyncBackendType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.Duration

/*
 * Behavior Contract:
 * - Unit under test: WebDAV and Git sync schedulers with production SyncWorkPolicy planners.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: schedule remote auto-sync by enqueueing backend-neutral SyncScheduledWork decisions instead of locally computing WorkManager cadence, constraints, work name, and existing-work policy.
 *
 * Scenarios:
 * - Given WebDAV sync and auto-sync are enabled, when reschedule runs, then the enqueued work carries WebDAV backend, worker lane, periodic cadence, connected-network requirement, replace policy, and standard remote payload from SyncWorkPolicy.
 * - Given Git sync and auto-sync are enabled, when reschedule runs, then the enqueued work carries Git backend, worker lane, periodic cadence, connected-network requirement, replace policy, and standard remote payload from SyncWorkPolicy.
 *
 * Observable outcomes:
 * - SyncScheduledWork captured at the scheduler/enqueuer boundary: backend, trigger, unique work name, cadence, network requirement, existing-work policy, payload, and coalescing key.
 *
 * TDD proof:
 * - RED: before the fix, this spec would not compile because WebDavSyncScheduler/GitSyncScheduler did not accept SyncWorkPolicy planners or scheduled-work enqueuer seams, and WebDAV/Git scheduled work was computed directly inside the scheduler.
 *
 * Excludes:
 * - Android WorkManager request construction, worker execution, repository sync transport, and disabled-state cancellation.
 */
class RemoteSyncSchedulerPolicyTest : DataFunSpec() {
    init {
        test("given webdav auto sync is enabled when rescheduled then scheduler enqueues policy-planned work") {
            runTest {
                val enqueuer = CapturingWebDavEnqueuer()
                val scheduler =
                    WebDavSyncScheduler(
                        context = context,
                        dataStore =
                            dataStore(
                                webDavSyncEnabled = true,
                                webDavAutoSyncEnabled = true,
                                webDavAutoSyncInterval = "12h",
                            ),
                        policyPlanner = WebDavSyncWorkPolicyPlanner(),
                        scheduledWorkEnqueuer = enqueuer,
                    )

                scheduler.reschedule()

                enqueuer.enqueuedWork shouldContainExactly
                    listOf(
                        SyncScheduledWork(
                            backend = SyncBackendType.WEBDAV,
                            trigger = SyncWorkTrigger.PERIODIC_AUTO_SYNC,
                            uniqueWorkName = WebDavSyncWorker.WORK_NAME,
                            cadence = SyncWorkCadence.Periodic(Duration.ofHours(12)),
                            networkRequirement = SyncWorkNetworkRequirement.Connected,
                            existingWorkPolicy = SyncExistingWorkPolicy.Replace,
                            payload = SyncWorkPayload.StandardRemoteSync,
                        ),
                    )
            }
        }

        test("given git auto sync is enabled when rescheduled then scheduler enqueues policy-planned work") {
            runTest {
                val enqueuer = CapturingGitEnqueuer()
                val scheduler =
                    GitSyncScheduler(
                        context = context,
                        dataStore =
                            dataStore(
                                gitSyncEnabled = true,
                                gitAutoSyncEnabled = true,
                                gitAutoSyncInterval = "30min",
                            ),
                        policyPlanner = GitSyncWorkPolicyPlanner(),
                        scheduledWorkEnqueuer = enqueuer,
                    )

                scheduler.reschedule()

                val work = enqueuer.enqueuedWork.single()
                assertSoftly(work) {
                    backend shouldBe SyncBackendType.GIT
                    trigger shouldBe SyncWorkTrigger.PERIODIC_AUTO_SYNC
                    uniqueWorkName shouldBe GitSyncWorker.WORK_NAME
                    cadence shouldBe SyncWorkCadence.Periodic(Duration.ofMinutes(30))
                    networkRequirement shouldBe SyncWorkNetworkRequirement.Connected
                    existingWorkPolicy shouldBe SyncExistingWorkPolicy.Replace
                    payload shouldBe SyncWorkPayload.StandardRemoteSync
                    coalescingKey.backend shouldBe SyncBackendType.GIT
                    coalescingKey.uniqueWorkName shouldBe GitSyncWorker.WORK_NAME
                }
            }
        }
    }

    private val context: Context = mockk()

    private fun dataStore(
        webDavSyncEnabled: Boolean = false,
        webDavAutoSyncEnabled: Boolean = false,
        webDavAutoSyncInterval: String = "1h",
        gitSyncEnabled: Boolean = false,
        gitAutoSyncEnabled: Boolean = false,
        gitAutoSyncInterval: String = "1h",
    ): LomoDataStore =
        mockk {
            every { this@mockk.webDavSyncEnabled } returns flowOf(webDavSyncEnabled)
            every { this@mockk.webDavAutoSyncEnabled } returns flowOf(webDavAutoSyncEnabled)
            every { this@mockk.webDavAutoSyncInterval } returns flowOf(webDavAutoSyncInterval)
            every { this@mockk.gitSyncEnabled } returns flowOf(gitSyncEnabled)
            every { this@mockk.gitAutoSyncEnabled } returns flowOf(gitAutoSyncEnabled)
            every { this@mockk.gitAutoSyncInterval } returns flowOf(gitAutoSyncInterval)
        }
}

private class CapturingWebDavEnqueuer : WebDavScheduledSyncWorkEnqueuer {
    val enqueuedWork = mutableListOf<SyncScheduledWork>()

    override suspend fun enqueue(work: List<SyncScheduledWork>) {
        enqueuedWork += work
    }
}

private class CapturingGitEnqueuer : GitScheduledSyncWorkEnqueuer {
    val enqueuedWork = mutableListOf<SyncScheduledWork>()

    override suspend fun enqueue(work: List<SyncScheduledWork>) {
        enqueuedWork += work
    }
}
