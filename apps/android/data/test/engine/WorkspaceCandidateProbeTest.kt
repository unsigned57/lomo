package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: WorkspaceCandidateProbe.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: reject blank/missing/non-writable Direct roots before freeze+persist; accept
 *   existing writable directories; fail closed for SAF without grant/write.
 *
 * Scenarios:
 * - Given a missing path, when validate runs, then it fails closed.
 * - Given an existing writable directory, when validate runs, then it succeeds.
 * - Given blank location, when validate runs, then it fails closed.
 * - Given a known non-writable directory, when validate runs, then it fails closed.
 * - Given content uri without grant, when validate runs, then it fails closed.
 *
 * Observable outcomes: exception vs success.
 * TDD proof: fails before production probe exists (blank-only default) and before canWrite check.
 * TDD proof (selected-directory boundary): target/lomo/reports/manual/directory-selection/candidate-red.log
 * accepts an unavailable child and a null listing; candidate-green.log covers typed rejection and empty directories.
 * Test Change Justification:
 * Reason category: explicit provider-validation failure.
 * Old behavior/assertion being replaced: missing grants accepted any Throwable, including a broken framework fixture.
 * Why old assertion is no longer correct: a missing grant must be rejected before any document query.
 * Coverage preserved by: all Direct cases and a stricter saf_grant_revoked assertion without relaxed collaborators.
 * Why this is not fitting the test to the implementation: the Android grant boundary must produce a classified failure.
 * Excludes: live SAF grant matrix.
 *
 * Test Change Justification:
 * - Reason category: SAF permission boundary correction.
 * - Old behavior/assertion being replaced: any persisted URI grant was treated as sufficient for a candidate tree.
 * - Why old assertion is no longer correct: the selected tree must be covered by a matching read/write grant.
 * - Coverage preserved by: direct-directory validation and invalid-candidate failures remain covered.
 * - Why this is not fitting the test to the implementation: assertions constrain accepted and rejected storage capabilities.
 */

import android.content.Context
import android.net.Uri
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.StorageLocation
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import java.io.File

class WorkspaceCandidateProbeTest : DataFunSpec() {
    init {
        test("given blank location when validate runs then fails closed") {
            runTest {
                val probe = WorkspaceCandidateProbe(
                    dispatcherProvider = com.lomo.domain.usecase.DefaultDispatcherProvider(),
                    context = mockk<Context>(),
                    isContentUri = { false },
                )
                shouldThrow<IllegalArgumentException> {
                    probe.validate(StorageLocation("   "))
                }.message.shouldContain("non-blank")
            }
        }

        test("given missing direct path when validate runs then fails closed") {
            runTest {
                val probe = WorkspaceCandidateProbe(
                    dispatcherProvider = com.lomo.domain.usecase.DefaultDispatcherProvider(),
                    context = mockk<Context>(),
                    isContentUri = { false },
                )
                val missing = File("/tmp/lomo-missing-candidate-${System.nanoTime()}")
                shouldThrow<WorkspaceCandidateValidationException> {
                    probe.validate(StorageLocation(missing.absolutePath))
                }.code shouldBe "workspace_root_unavailable"
            }
        }

        test("given existing direct directory when validate runs then succeeds") {
            runTest {
                val dir = kotlin.io.path.createTempDirectory("ws-candidate-ok").toFile()
                try {
                    val probe = WorkspaceCandidateProbe(
                        dispatcherProvider = com.lomo.domain.usecase.DefaultDispatcherProvider(),
                        context = mockk<Context>(),
                        isContentUri = { false },
                    )
                    shouldNotThrowAny {
                        probe.validate(StorageLocation(dir.absolutePath))
                    }
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test("given known non-writable direct directory when validate runs then fails closed") {
            runTest {
                // /proc is a directory on Linux hosts and is not user-writable.
                val proc = File("/proc")
                require(proc.isDirectory && !proc.canWrite()) {
                    "Host must expose a non-writable directory for this contract"
                }
                val probe = WorkspaceCandidateProbe(
                    dispatcherProvider = com.lomo.domain.usecase.DefaultDispatcherProvider(),
                    context = mockk<Context>(),
                    isContentUri = { false },
                )
                shouldThrow<WorkspaceCandidateValidationException> {
                    probe.validate(StorageLocation(proc.absolutePath))
                }.code shouldBe "workspace_root_unwritable"
            }
        }

        test("given content uri without grant when validate runs then fails closed") {
            runTest {
                val raw = "content://tree/primary%3AMissing"
                val uri = mockk<Uri>()
                mockkStatic(Uri::class)
                every { Uri.parse(raw) } returns uri
                val context = mockk<Context>()
                every { context.contentResolver.persistedUriPermissions } returns emptyList()
                val probe =
                    WorkspaceCandidateProbe(
                        dispatcherProvider = com.lomo.domain.usecase.DefaultDispatcherProvider(),
                        context = context,
                        isContentUri = { it.startsWith("content://") },
                    )
                try {
                    shouldThrow<WorkspaceCandidateValidationException> {
                        probe.validate(StorageLocation(raw))
                    }.code shouldBe "saf_grant_revoked"
                } finally {
                    unmockkStatic(Uri::class)
                }
            }
        }

        test("given nested subfolder uri when uriTreesMatch runs then matches accurately") {
            val granted = mockk<android.net.Uri>()
            every { granted.toString() } returns
                "content://com.android.externalstorage.documents/tree/primary%3ATest%2FJournals"
            val candidate = "content://com.android.externalstorage.documents/tree/primary%3ATest%2FJournals"
            val decodedCandidate = "content://com.android.externalstorage.documents/tree/primary:Test/Journals"
            val otherCandidate = "content://com.android.externalstorage.documents/tree/primary%3ATest%2FOther"

            uriTreesMatch(granted, candidate) shouldBe true
            uriTreesMatch(granted, decodedCandidate) shouldBe true
            uriTreesMatch(granted, otherCandidate) shouldBe false
        }
    }
}
