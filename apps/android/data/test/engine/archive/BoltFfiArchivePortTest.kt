/*
 * Behavior Contract:
 * Capability: temporary media grants for a portable archive; owner: data; priority: P0.
 * Scenarios: Given separately selected media roots, when export succeeds or fails, then the
 * native call receives resolving capabilities and every temporary capability is revoked afterward.
 * Observable outcomes: archive receipt, resolved roots during export, unknown-capability errors
 * after completion, and the original exception on failure.
 * TDD proof: lomo-extra-media-red.log omitted z.png from a separate root; Rust's matching GREEN
 * validates archive entries, while these cases verify the Android grant lifetime at that boundary.
 * Excludes: provider stream behavior and Compose navigation.
 */
package com.lomo.data.engine.archive

import com.lomo.data.engine.CapabilityRegistry
import com.lomo.data.engine.CapabilityRegistryException
import com.lomo.data.engine.SafCapabilityGrant
import com.lomo.data.testing.DataFunSpec
import com.lomo.nativebridge.ArchiveExportResultDto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk

class BoltFfiArchivePortTest : DataFunSpec() {
    init {
        test("given independent media roots when exported then the receipt survives temporary grant revocation") {
            val registry = CapabilityRegistry()
            val bridge = mockk<ArchiveNativeBridge>()
            val roots = listOf("content://test.provider/tree/images", "content://test.provider/tree/voice")
            var tokens = emptyList<String>()
            every { bridge.sessionExportArchive(null, any()) } answers {
                tokens = secondArg()
                tokens.map { registry.resolve(it).shouldBeInstanceOf<SafCapabilityGrant>().treeUri } shouldBe roots
                ArchiveExportResultDto("/private/export.zip", 2u, 3uL)
            }
            val port = BoltFfiArchivePort(bridge, registry)

            port.sessionExportArchive(null, roots) shouldBe ArchiveExportResult("/private/export.zip", 2, 3)
            tokens.size shouldBe 2
            tokens.forEach { token -> shouldThrow<CapabilityRegistryException> { registry.resolve(token) } }
        }

        test("given a native export failure when unwound then temporary grants are revoked and failure is preserved") {
            val registry = CapabilityRegistry()
            val bridge = mockk<ArchiveNativeBridge>()
            var tokens = emptyList<String>()
            val failure = IllegalStateException("media changed during export")
            every { bridge.sessionExportArchive(null, any()) } answers {
                tokens = secondArg()
                throw failure
            }
            val port = BoltFfiArchivePort(bridge, registry)

            shouldThrow<IllegalStateException> {
                port.sessionExportArchive(null, listOf("content://test.provider/tree/images"))
            } shouldBe failure
            tokens.size shouldBe 1
            tokens.forEach { token -> shouldThrow<CapabilityRegistryException> { registry.resolve(token) } }
        }
    }
}
