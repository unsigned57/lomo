package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: ProcessWorkspaceMutationLease.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: admit workspace mutations as registered writers so a workspace switch can refuse
 *   new writers and drain the ones already admitted before the workspace changes.
 *
 * Scenarios:
 * - Given a Ready engine, when withWrite runs, then the block receives the published authority.
 * - Given a writer that is already admitted and still running, when a transition starts, then the
 *   transition body does not run until that writer releases.
 * - Given an active transition, when a new writer asks for admission, then withWrite fails closed
 *   and withWriteOrNull reports the refusal without running its block.
 * - Given a non-Ready engine, when withWrite runs, then it fails closed before the block runs.
 * - Given a transition body that throws, when it fails, then admissions reopen.
 * - Given a writer that nests another withWrite, when the inner admission is requested, then it
 *   reuses the outer one instead of taking a second registration.
 *
 * Observable outcomes:
 * - Block execution order, thrown failures, admissibility after each phase.
 *
 * TDD proof:
 * - RED on 2026-07-27: the previous WorkspaceWriteAuthority only reported an instantaneous
 *   Ready+!frozen boolean, so a switch began while a writer that had already passed the check was
 *   still mutating the old workspace, and no drain existed to wait for.
 *
 * Excludes:
 * - Engine activation itself, durable selection persistence, and file writes.
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.WorkspaceAuthority
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessWorkspaceMutationLeaseTest : DataFunSpec() {
    init {
        test("given Ready engine when withWrite runs then the published authority is admitted") {
            runTest {
                val readiness = FakeEngineReadinessRepository()
                val lease = ProcessWorkspaceMutationLease(readiness)

                val admitted = lease.withWrite { authority -> authority }

                admitted shouldBe WorkspaceAuthority(workspaceId = "fake-workspace", generation = 0)
                lease.isWritable() shouldBe true
            }
        }

        test("given an in-flight writer when a transition starts then it waits for the drain") {
            runTest {
                val lease = ProcessWorkspaceMutationLease(FakeEngineReadinessRepository())
                val writerAdmitted = CompletableDeferred<Unit>()
                val releaseWriter = CompletableDeferred<Unit>()
                val order = mutableListOf<String>()

                val writer =
                    async(UnconfinedTestDispatcher(testScheduler)) {
                        lease.withWrite {
                            writerAdmitted.complete(Unit)
                            releaseWriter.await()
                            order += "writer-released"
                        }
                    }
                writerAdmitted.await()

                val transition =
                    async(UnconfinedTestDispatcher(testScheduler)) {
                        lease.withExclusiveTransition { order += "transition-ran" }
                    }
                testScheduler.advanceUntilIdle()

                // The transition must not have entered its body while the writer is still admitted.
                order shouldBe emptyList()
                releaseWriter.complete(Unit)
                writer.await()
                transition.await()

                order shouldBe listOf("writer-released", "transition-ran")
            }
        }

        test("given an active transition when a new writer arrives then admission is refused") {
            runTest {
                val lease = ProcessWorkspaceMutationLease(FakeEngineReadinessRepository())
                var refusedBlockRan = false

                lease.withExclusiveTransition {
                    lease.isWritable() shouldBe false
                    // A nested lease call from the transition's own coroutine would inherit an
                    // admission if one existed; the transition holds none, so this is a real writer.
                    val refused =
                        withContext(UnconfinedTestDispatcher(testScheduler)) {
                            shouldThrow<IllegalStateException> {
                                lease.withWrite { error("must not run") }
                            }
                        }
                    refused.message.shouldContain("switch is in progress")

                    val skipped =
                        withContext(UnconfinedTestDispatcher(testScheduler)) {
                            lease.withWriteOrNull {
                                refusedBlockRan = true
                                "written"
                            }
                        }
                    skipped shouldBe null
                }

                refusedBlockRan shouldBe false
                lease.isWritable() shouldBe true
            }
        }

        test("given a non-Ready engine when withWrite runs then it fails closed before the block") {
            runTest {
                val lease =
                    ProcessWorkspaceMutationLease(
                        FakeEngineReadinessRepository(EngineReadiness.AwaitingWorkspaceSelection),
                    )
                var blockRan = false

                val error =
                    shouldThrow<IllegalStateException> {
                        lease.withWrite { blockRan = true }
                    }

                error.message.shouldContain("awaiting workspace selection")
                blockRan shouldBe false
                lease.withWriteOrNull { blockRan = true } shouldBe null
                blockRan shouldBe false
            }
        }

        test("given a failing transition body when it throws then admissions reopen") {
            runTest {
                val lease = ProcessWorkspaceMutationLease(FakeEngineReadinessRepository())

                shouldThrow<IllegalStateException> {
                    lease.withExclusiveTransition { error("activation refused") }
                }

                lease.isWritable() shouldBe true
                lease.withWrite { "admitted again" } shouldBe "admitted again"
            }
        }

        test("given a nested writer when it asks for admission then it reuses the outer one") {
            runTest {
                val lease = ProcessWorkspaceMutationLease(FakeEngineReadinessRepository())

                val nested =
                    lease.withWrite { outer ->
                        lease.withWrite { inner -> outer to inner }
                    }

                nested.first shouldBe nested.second
                // The outer admission was released exactly once, so a later transition can drain.
                lease.withExclusiveTransition { "drained" } shouldBe "drained"
            }
        }
    }
}
