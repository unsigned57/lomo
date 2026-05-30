package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.SyncBackendType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: RemoteSyncLifecycleRunner
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: provider-neutral remote sync lifecycle ordering and result/error mapping.
 *
 * Scenarios:
 * - Given provider stages, when lifecycle runs, then snapshot, plan, verify, conflict materialization,
 *   apply, metadata commit, finalize, and result mapping execute in the canonical order.
 * - Given conflict materialization returns conflicts, when lifecycle runs, then apply and metadata commit
 *   still receive the materialized conflict state.
 * - Given a stage fails, when lifecycle runs, then the runner maps the failure through the provider adapter.
 * - Given cancellation occurs, when lifecycle runs, then cancellation is propagated instead of mapped.
 * - Given lifecycle stages finish or fail, when the runner exits, then provider resources are released.
 * - Given provider stages report lifecycle summaries and network work through the shared session, when
 *   lifecycle runs, then one aggregated telemetry event contains snapshot counts, planned/verified
 *   actions, network operation counts, refresh/metadata timings, and budget consumption.
 * - Given the shared network-operation budget is exhausted, when provider work requests another
 *   network operation through the lifecycle session, then the provider maps a central budget failure.
 *
 * Observable outcomes:
 * - Ordered stage log, returned mapped result, conflict propagation into later stages, mapped failure result,
 *   emitted lifecycle telemetry, and central budget rejection.
 *
 * TDD proof:
 * - RED: `./gradlew :data:testDebugUnitTest --tests 'com.lomo.data.repository.RemoteSyncLifecycleRunnerTest'`
 *   fails to compile before the fix because the data-owned lifecycle runner/stage contract does not exist.
 * - RED: `GRADLE_USER_HOME="$PWD/.gradle/task-inspect" ./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.repository.RemoteSyncLifecycleRunnerTest'`
 *   fails before the telemetry/budget owner exists because `SyncLifecycleExecutionOwner` and metered
 *   lifecycle sessions are unresolved.
 *
 * Excludes:
 * - WebDAV/S3 transport details, file bridge behavior, Room persistence, and domain provider registry wiring.
 */
class RemoteSyncLifecycleRunnerTest : DataFunSpec() {
    init {
        test("given provider stages when lifecycle runs then canonical ordering and result mapping are shared") {
            runTest {
                val stages = RecordingLifecycleStages()
                val runner = DefaultRemoteSyncLifecycleRunner(RecordingSyncLifecycleExecutionOwner())

                val result = runner.run(stages)

                stages.events shouldContainExactly
                    listOf(
                        "snapshot",
                        "plan:snapshot",
                        "verify:plan",
                        "conflicts:verified",
                        "apply:verified:conflicts",
                        "commit:verified:conflicts:applied",
                        "finalize:verified:conflicts:applied:metadata",
                        "result:finalized",
                        "release",
                    )
                result shouldBe "result:finalized"
            }
        }

        test("given stage failure when lifecycle runs then provider error mapping owns the returned result") {
            runTest {
                val stages = RecordingLifecycleStages(failAt = "apply")
                val runner = DefaultRemoteSyncLifecycleRunner(RecordingSyncLifecycleExecutionOwner())

                val result = runner.run(stages)

                result shouldBe "error:apply failed"
                stages.events shouldContainExactly
                    listOf(
                        "snapshot",
                        "plan:snapshot",
                        "verify:plan",
                        "conflicts:verified",
                        "apply:verified:conflicts",
                        "release",
                        "error:apply failed",
                    )
            }
        }

        test("given cancellation when lifecycle runs then cancellation is propagated") {
            runTest {
                val stages = RecordingLifecycleStages(failAt = "verify", cancellation = true)
                val runner = DefaultRemoteSyncLifecycleRunner(RecordingSyncLifecycleExecutionOwner())

                val failure =
                    kotlin.runCatching {
                        runner.run(stages)
                    }.exceptionOrNull()

                failure shouldBe CancellationException("verify cancelled")
                stages.events shouldContainExactly
                    listOf(
                        "snapshot",
                        "plan:snapshot",
                        "verify:plan",
                        "release",
                    )
            }
        }

        test("given lifecycle summaries and metered network work when lifecycle runs then telemetry is aggregated once") {
            runTest {
                val owner = RecordingSyncLifecycleExecutionOwner()
                val stages = RecordingLifecycleStages(networkOperations = listOf("list", "head", "get", "put", "delete"))
                val runner = DefaultRemoteSyncLifecycleRunner(owner)

                val result = runner.run(stages)

                result shouldBe "result:finalized"
                owner.reports shouldHaveSize 1
                owner.reports.single() shouldBe
                    RemoteSyncLifecycleTelemetry(
                        backend = SyncBackendType.S3,
                        snapshot = RemoteSyncSnapshotTelemetry(
                            localFileCount = 2,
                            remoteFileCount = 3,
                            metadataEntryCount = 1,
                        ),
                        plannedActions = RemoteSyncActionTelemetry(total = 5, upload = 1, download = 1, deleteLocal = 1, deleteRemote = 1, conflict = 1),
                        verifiedActions = RemoteSyncActionTelemetry(total = 4, upload = 1, download = 1, deleteLocal = 1, deleteRemote = 1),
                        network = RemoteSyncNetworkTelemetry(list = 1, head = 1, get = 1, put = 1, delete = 1),
                        refreshDurationMillis = 0,
                        metadataCommitDurationMillis = 0,
                        budget = RemoteSyncBudgetTelemetry(
                            decision = RemoteSyncBudgetDecision.Allowed,
                            consumedNetworkOperations = 5,
                            remainingNetworkOperations = 95,
                        ),
                        result = RemoteSyncLifecycleResultTelemetry.Success,
                    )
            }
        }

        test("given lifecycle network budget is exhausted when provider requests more work then central owner maps rejection") {
            runTest {
                val owner = RecordingSyncLifecycleExecutionOwner(maxNetworkOperations = 1)
                val stages = RecordingLifecycleStages(networkOperations = listOf("list", "head"))
                val runner = DefaultRemoteSyncLifecycleRunner(owner)

                val result = runner.run(stages)

                result shouldBe "error:Remote sync network operation budget exhausted for S3"
                owner.reports.single().budget.decision shouldBe RemoteSyncBudgetDecision.Exhausted
                owner.reports.single().network shouldBe RemoteSyncNetworkTelemetry(list = 1)
            }
        }
    }
}

private class RecordingLifecycleStages(
    private val failAt: String? = null,
    private val cancellation: Boolean = false,
    private val networkOperations: List<String> = emptyList(),
) : RemoteSyncLifecycleStages<String, String, String, String, String, String, String, String> {
    val events = mutableListOf<String>()

    override val context: RemoteSyncLifecycleContext =
        RemoteSyncLifecycleContext(
            backend = SyncBackendType.S3,
            budget = RemoteSyncBudgetPolicy.Limited(maxNetworkOperations = 100),
        )

    override suspend fun loadSnapshot(session: RemoteSyncLifecycleSession): String {
        events += "snapshot"
        networkOperations.forEach { operation ->
            session.recordNetworkOperation(operation.toRemoteSyncNetworkOperation())
        }
        maybeFail("snapshot")
        return "snapshot"
    }

    override suspend fun plan(
        snapshot: String,
        session: RemoteSyncLifecycleSession,
    ): String {
        events += "plan:$snapshot"
        maybeFail("plan")
        return "plan"
    }

    override suspend fun verify(
        plan: String,
        session: RemoteSyncLifecycleSession,
    ): String {
        events += "verify:$plan"
        maybeFail("verify")
        return "verified"
    }

    override suspend fun materializeConflicts(
        verified: String,
        session: RemoteSyncLifecycleSession,
    ): String {
        events += "conflicts:$verified"
        maybeFail("conflicts")
        return "conflicts"
    }

    override suspend fun apply(
        verified: String,
        conflicts: String,
        session: RemoteSyncLifecycleSession,
    ): String {
        events += "apply:$verified:$conflicts"
        maybeFail("apply")
        return "applied"
    }

    override suspend fun commitMetadata(
        verified: String,
        conflicts: String,
        applied: String,
        session: RemoteSyncLifecycleSession,
    ): String {
        events += "commit:$verified:$conflicts:$applied"
        maybeFail("commit")
        return "metadata"
    }

    override suspend fun finalize(
        verified: String,
        conflicts: String,
        applied: String,
        metadata: String,
        session: RemoteSyncLifecycleSession,
    ): String {
        events += "finalize:$verified:$conflicts:$applied:$metadata"
        maybeFail("finalize")
        return "finalized"
    }

    override fun summarizeSnapshot(snapshot: String): RemoteSyncSnapshotTelemetry =
        RemoteSyncSnapshotTelemetry(
            localFileCount = 2,
            remoteFileCount = 3,
            metadataEntryCount = 1,
        )

    override fun summarizePlan(plan: String): RemoteSyncActionTelemetry =
        RemoteSyncActionTelemetry(total = 5, upload = 1, download = 1, deleteLocal = 1, deleteRemote = 1, conflict = 1)

    override fun summarizeVerification(verified: String): RemoteSyncActionTelemetry =
        RemoteSyncActionTelemetry(total = 4, upload = 1, download = 1, deleteLocal = 1, deleteRemote = 1)

    override fun summarizeRefresh(finalized: String): RemoteSyncRefreshTelemetry =
        RemoteSyncRefreshTelemetry(durationMillis = 0)

    override fun mapResult(finalized: String): String {
        events += "result:$finalized"
        maybeFail("result")
        return "result:$finalized"
    }

    override fun mapError(error: Throwable): String {
        events += "error:${error.message}"
        return "error:${error.message}"
    }

    override suspend fun release() {
        events += "release"
    }

    private fun maybeFail(stage: String) {
        if (failAt != stage) {
            return
        }
        if (cancellation) {
            throw CancellationException("$stage cancelled")
        }
        throw IllegalStateException("$stage failed")
    }
}

private fun String.toRemoteSyncNetworkOperation(): RemoteSyncNetworkOperation =
    when (this) {
        "list" -> RemoteSyncNetworkOperation.List
        "head" -> RemoteSyncNetworkOperation.Head
        "get" -> RemoteSyncNetworkOperation.Get
        "put" -> RemoteSyncNetworkOperation.Put
        "delete" -> RemoteSyncNetworkOperation.Delete
        else -> error("Unsupported operation $this")
    }

private class RecordingSyncLifecycleExecutionOwner(
    private val maxNetworkOperations: Int = 100,
) : SyncLifecycleExecutionOwner {
    val reports = mutableListOf<RemoteSyncLifecycleTelemetry>()

    override fun begin(context: RemoteSyncLifecycleContext): RemoteSyncLifecycleSession =
        DefaultRemoteSyncLifecycleSession(
            context = context.copy(budget = RemoteSyncBudgetPolicy.Limited(maxNetworkOperations)),
            clock = { 0L },
            emit = reports::add,
        )
}
