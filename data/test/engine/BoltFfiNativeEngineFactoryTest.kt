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
 * - Given a blank SAF capability token, when a Saf selection is constructed, then construction
 *   fails closed at the Kotlin boundary.
 *
 * Observable outcomes:
 * - request path layout and validation exceptions.
 *
 * TDD proof:
 * - RED when production open has no factory and no app-root layout contract.
 *
 * Excludes:
 * - Live LomoEngine.open (requires packaged native library; covered by native-smoke / device).
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

        test("given blank SAF token when selection is constructed then boundary rejects") {
            shouldThrow<IllegalArgumentException> {
                NativeWorkspaceSelection.Saf(capabilityToken = "  ")
            }
        }

        test("given direct workspace path when selection is constructed then directory is created") {
            val root = kotlin.io.path.createTempDirectory("lomo-direct-ws").toFile()
            try {
                val nested = File(root, "nested-workspace")
                val selection = NativeWorkspaceSelection.Direct(nested)
                selection.rootPath.isDirectory shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
