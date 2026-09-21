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

import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class BoltFfiNativeEngineFactoryTest : DataFunSpec() {
    init {
        test("given app filesDir when open request is built then control and exchange roots exist under lomo-engine v1") {
            val filesDir = kotlin.io.path.createTempDirectory("lomo-engine-factory").toFile()
            try {
                val request = NativeEngineOpenRequest.forAppFilesDir(filesDir)

                request.controlRoot.isDirectory shouldBe true
                request.exchangeRoot.isDirectory shouldBe true
                request.controlRoot.path shouldContain "lomo-engine/v1/control"
                request.exchangeRoot.path shouldContain "lomo-engine/v1/exchange"
                request.workspace shouldBe null
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
    }
}
