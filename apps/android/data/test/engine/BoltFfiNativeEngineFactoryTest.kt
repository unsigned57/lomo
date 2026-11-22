package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: BoltFfiNativeEngineFactory / NativeEngineOpenRequest.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: construct the production open request and refuse invalid workspace roots before
 *   any generated BoltFFI call.
 *
 * Scenarios:
 * - Given an app filesDir, when forAppFilesDir builds a request, then control and exchange roots
 *   exist under lomo-engine/v1 and workspace is unset (awaiting selection).
 * - Given a registry-bound SAF grant, when a Saf selection is constructed, then its stable identity
 *   and process capability remain the inseparable FFI input.
 * - Given a missing direct root, when registered, then the directory is not created and bind fails.
 * - Given a registry-bound Direct grant, when a Direct selection is constructed, then its stable
 *   identity and process capability remain the inseparable FFI input.
 *
 * Observable outcomes:
 * - request path layout, bound SAF/Direct selection values, and filesystem side effects of bind.
 *
 * TDD proof:
 * - Stable-identity RED is recorded by CapabilityRegistryTest and ManagedEngineSessionTest; this
 *   companion spec locks the inseparable grant shape at the native selection boundary.
 * - RED on 2026-07-27: constructing a Direct selection ran `mkdirs()`, so naming an unmounted or
 *   deleted root materialised an empty workspace instead of failing closed into Recovery.
 *
 * Excludes:
 * - Live LomoEngine.open (requires packaged native library).
 *
 * Test Change Justification:
 * - Reason category: Direct root capability registration.
 * - Old behavior/assertion being replaced: Direct selection could be constructed from a bare File
 *   without a registered capability, and missing-root construction was asserted as a pure description.
 * - Why old assertion is no longer correct: Direct IO uses the same registered token as session
 *   config and platform actions. A missing root must fail at bind, still without creating the path.
 * - Coverage preserved by: missing-root bind still asserts the directory is not created; SAF grant
 *   pairing remains covered.
 * - Why this is not fitting the test to the implementation: the observable product contract is that
 *   Direct has a real grant, not a sentinel token or an unbound path.
 */

import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineReadiness
import com.lomo.nativebridge.EngineError
import com.lomo.nativebridge.EngineFailure
import com.lomo.nativebridge.EngineState
import com.lomo.nativebridge.JobStep
import com.lomo.nativebridge.LanServicePhaseDto
import com.lomo.nativebridge.LanServiceSnapshotDto
import com.lomo.nativebridge.LomoEngine
import com.lomo.nativebridge.PlatformAction
import com.lomo.nativebridge.PlatformActionBatch
import com.lomo.nativebridge.SessionOpenResult
import com.lomo.nativebridge.ShutdownOutcome
import com.lomo.nativebridge.WorkspaceTarget
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import java.io.File
import java.util.concurrent.Executors

class BoltFfiNativeEngineFactoryTest : DataFunSpec() {
    init {
        test(
            "given app filesDir when open request is built then control and exchange roots exist under lomo-engine v1",
        ) {
            val filesDir = kotlin.io.path.createTempDirectory("lomo-engine-factory").toFile()
            try {
                val request = NativeEngineOpenRequest.forAppFilesDir(filesDir)

                request.controlRoot.isDirectory shouldBe true
                request.exchangeRoot.isDirectory shouldBe true
                request.controlRoot.path shouldContain "lomo-engine/v1/control"
                request.exchangeRoot.path shouldContain "lomo-engine/v1/exchange"
                request.workspace shouldBe null
                // Bootstrap never opens a workspace session, so it carries no date token.
                request.dateFormat shouldBe null
                request.bootstrapDeadlineMillis shouldBe
                    NativeEngineOpenRequest.DEFAULT_BOOTSTRAP_DEADLINE_MILLIS
            } finally {
                filesDir.deleteRecursively()
            }
        }

        test("given bound SAF grant when selection is constructed then identity and token stay paired") {
            val grant =
                CapabilityRegistry().register(
                    token = "cap-selection",
                    treeUri = "content://com.lomo.documents/tree/primary%3ALomo",
                )

            val selection = NativeWorkspaceSelection.Saf(grant)

            selection.capabilityToken shouldBe "cap-selection"
            selection.stableWorkspaceId shouldBe grant.stableWorkspaceId
        }

        test("given missing direct root when registered then the directory is not created") {
            val root = kotlin.io.path.createTempDirectory("lomo-direct-ws").toFile()
            try {
                val missing = File(root, "nested-workspace")
                val error =
                    shouldThrow<CapabilityRegistryException> {
                        CapabilityRegistry().registerDirect(token = "cap-direct-missing", rootPath = missing)
                    }

                error.code shouldBe "workspace_root_not_directory"
                missing.exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("given bound Direct grant when selection is constructed then identity and token stay paired") {
            val root = kotlin.io.path.createTempDirectory("lomo-direct-selection").toFile()
            try {
                val grant =
                    CapabilityRegistry().registerDirect(
                        token = "cap-direct-selection",
                        rootPath = root,
                    )

                val selection = NativeWorkspaceSelection.Direct(grant)

                selection.capabilityToken shouldBe "cap-direct-selection"
                selection.stableWorkspaceId shouldBe grant.stableWorkspaceId
                selection.rootPath shouldBe root.canonicalFile
            } finally {
                root.deleteRecursively()
            }
        }

        test("given host execution throws unexpectedly when batch runs then a typed EngineError crosses the boundary") {
            val host =
                BoltFfiNativeEngineFactory.SessionPlatformBatchHost(
                    AndroidPlatformActionExecutor(
                        access =
                        PlatformActionAccess {
                            throw IllegalStateException("provider exploded")
                        },
                        currentTimeMillis = { 1_000L },
                        readExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(),
                    ),
                )
            val batch =
                PlatformActionBatch(
                    schemaVersion = 1u,
                    jobId = "job-boundary",
                    batchId = "batch-boundary",
                    attempt = 1u,
                    deadlineEpochMillis = 2_000uL,
                    actions =
                    listOf(
                        PlatformAction.Stat("action-1", "cap", WorkspaceTarget.Root),
                    ),
                )

            val error = shouldThrow<EngineError.Failure> { host.execute(batch) }

            error.failure.code shouldBe "platform_batch_host_error"
            error.failure.jobId shouldBe "job-boundary"
            error.failure.diagnostic shouldContain "provider exploded"
        }

        test("given a rejected SAF probe when opening its workspace then the original rejection reaches the caller") {
            val filesDir = kotlin.io.path.createTempDirectory("lomo-engine-saf-refusal").toFile()
            val readExecutor = Executors.newSingleThreadExecutor()
            try {
                val grant = CapabilityRegistry().register(treeUri = "content://com.example/tree/notes")
                val request = NativeEngineOpenRequest.forAppFilesDir(filesDir).copy(
                    workspace = NativeWorkspaceSelection.Saf(grant),
                    dateFormat = "yyyy_MM_dd",
                )
                val executor = AndroidPlatformActionExecutor(
                    access = PlatformActionAccess { error("The probe has already failed") },
                    currentTimeMillis = { 1_000L },
                    readExecutor = readExecutor,
                )
                val engine = mockk<LomoEngine>()
                every { engine.state() } returnsMany listOf(EngineState.Opening("job-1"), EngineState.Ready(0uL, 1uL))
                every { engine.pollJob("job-1") } returns JobStep.Failed(
                    EngineFailure("permission", "saf_grant_revoked", "after_user_action", null, "job-1", ""),
                )
                every { engine.stopLanService() } returns LanServiceSnapshotDto(LanServicePhaseDto.STOPPED, null)
                every { engine.shutdown(any()) } returns ShutdownOutcome.COMPLETED
                every { engine.close() } returns Unit

                mockkObject(LomoEngine.Companion) {
                    every { LomoEngine.open(any()) } returns engine
                    val failure = shouldThrow<EngineCommandFailureException> {
                        BoltFfiNativeEngineFactory.openAdapter(request, executor, StoreInvalidationBus()).use { }
                    }
                    failure.failure.code shouldBe "saf_grant_revoked"
                    failure.failure.jobId shouldBe "job-1"
                }
            } finally {
                readExecutor.shutdownNow()
                filesDir.deleteRecursively()
            }
        }

        test("given a failed native session open when the same workspace is retried then its owner is available") {
            val filesDir = kotlin.io.path.createTempDirectory("lomo-engine-retry").toFile()
            val readExecutor = Executors.newSingleThreadExecutor()
            try {
                val grant = CapabilityRegistry().registerDirect(rootPath = filesDir)
                val request = NativeEngineOpenRequest.forAppFilesDir(filesDir).copy(
                    workspace = NativeWorkspaceSelection.Direct(grant),
                    dateFormat = "yyyy_MM_dd",
                )
                val executor = AndroidPlatformActionExecutor(
                    access = PlatformActionAccess { error("Direct bootstrap needs no platform action") },
                    currentTimeMillis = { 1_000L },
                    readExecutor = readExecutor,
                )
                val engine = mockk<LomoEngine>()
                var locked = false
                every { engine.state() } returns EngineState.Ready(0uL, 1uL)
                every { engine.openWorkspaceSession(any(), any(), any(), any()) } throws
                    EngineError.Failure(EngineFailure("resource_limit", "ir_string_too_large", "never", null, null, ""))
                every { engine.stopLanService() } returns LanServiceSnapshotDto(LanServicePhaseDto.STOPPED, null)
                every { engine.shutdown(any()) } returns ShutdownOutcome.COMPLETED
                every { engine.close() } answers { locked = false }

                mockkObject(LomoEngine.Companion) {
                    every { LomoEngine.open(any()) } answers {
                        check(!locked) { "workspace owner is still locked" }
                        locked = true
                        engine
                    }
                    val invalidation = StoreInvalidationBus()
                    val failure = shouldThrow<EngineCommandFailureException> {
                        BoltFfiNativeEngineFactory.openAdapter(request, executor, invalidation)
                    }
                    failure.failure.code shouldBe "ir_string_too_large"

                    every { engine.openWorkspaceSession(any(), any(), any(), any()) } returns
                        SessionOpenResult(deviceId = "device", projectionRevision = 1uL, projectionVerified = true)
                    BoltFfiNativeEngineFactory.openAdapter(request, executor, invalidation).use { adapter ->
                        adapter.readiness.value shouldBe EngineReadiness.Ready
                    }
                }
            } finally {
                readExecutor.shutdownNow()
                filesDir.deleteRecursively()
            }
        }

        test("normalizes fixed-offset and UTC ZoneIds for Rust engine open") {
            normalizeEngineTimeZone(java.time.ZoneId.of("GMT+11:00")) shouldBe "+11:00"
            normalizeEngineTimeZone(java.time.ZoneId.of("UTC+00:00")) shouldBe "UTC"
            normalizeEngineTimeZone(java.time.ZoneId.of("Z")) shouldBe "UTC"
            normalizeEngineTimeZone(java.time.ZoneId.of("Australia/Sydney")) shouldBe "Australia/Sydney"
        }
    }
}
