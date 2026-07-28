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
 * - Given a missing direct root, when a Direct selection is constructed, then no directory is
 *   created and the selection stays a pure description of the location.
 *
 * Observable outcomes:
 * - request path layout, bound SAF selection values, and filesystem side effects of selection.
 *
 * TDD proof:
 * - Stable-identity RED is recorded by CapabilityRegistryTest and ManagedEngineSessionTest; this
 *   companion spec locks the inseparable grant shape at the native selection boundary.
 * - RED on 2026-07-27: constructing a Direct selection ran `mkdirs()`, so naming an unmounted or
 *   deleted root materialised an empty workspace instead of failing closed into Recovery.
 *
 * Excludes:
 * - Live LomoEngine.open (requires packaged native library; covered by native-smoke / device).
 *
 * Test Change Justification:
 * - Reason category: SAF identity/capability contract correction; Direct selection purity.
 * - Old behavior/assertion being replaced: Saf selection independently rejected a blank token, and
 *   Direct selection asserted that constructing it created the workspace directory.
 * - Why old assertion is no longer correct: only CapabilityRegistry can create a bound SAF grant;
 *   it owns token validation and stable tree identity derivation before selection construction.
 *   A selection describes a location, so it must never bring that location into existence —
 *   creating the root is what turned "notes are gone" into a Ready empty workspace.
 * - Coverage preserved by: CapabilityRegistryTest retains blank-token rejection; existence and
 *   writability of a candidate root are owned by WorkspaceCandidateProbe.
 * - Why this is not fitting the test to the implementation: the observable boundary is stronger;
 *   an unbound token can no longer be represented as a Saf selection, and selection construction
 *   can no longer have a filesystem side effect.
 */

import com.lomo.data.testing.DataFunSpec
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

        test("given missing direct root when selection is constructed then no directory is created") {
            val root = kotlin.io.path.createTempDirectory("lomo-direct-ws").toFile()
            try {
                val missing = File(root, "nested-workspace")

                val selection = NativeWorkspaceSelection.Direct(missing)

                selection.rootPath shouldBe missing
                missing.exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
