package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: CapabilityRegistry.
 * - Owning layer: data Android capability edge.
 * - Priority tier: P0.
 * - Capability: map opaque capability tokens to persisted SAF tree URI strings without exposing
 *   URIs to Rust, and fail closed for unknown or revoked grants.
 *
 * Scenarios:
 * - Given a registered token and tree URI string, when resolved, then the same string is returned.
 * - Given an unknown token, when resolved, then a structured permission failure is returned.
 * - Given a revoked token, when resolved, then resolution fails as if the grant never existed.
 * - Given a blank/invalid token or blank URI, when registered, then construction fails closed.
 *
 * Observable outcomes:
 * - resolved URI string identity and structured registry failures.
 *
 * TDD proof:
 * - RED: CapabilityRegistry does not exist.
 *
 * Excludes:
 * - ContentResolver permission probing, platform action execution, and UI grant pickers.
 */

import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

class CapabilityRegistryTest : DataFunSpec() {
    init {
        test("given registered token when resolved then tree URI string is returned") {
            val registry = CapabilityRegistry()
            val uri = "content://com.lomo.nativesmoke.documents/tree/root"

            registry.register(token = "saf-root-1", treeUri = uri)

            registry.resolve("saf-root-1") shouldBe uri
        }

        test("given unknown or revoked token when resolved then permission failure is structured") {
            val registry = CapabilityRegistry()
            registry.register(
                token = "saf-root-1",
                treeUri = "content://com.lomo.nativesmoke.documents/tree/root",
            )
            registry.revoke("saf-root-1")

            val unknown = shouldThrow<CapabilityRegistryException> { registry.resolve("missing") }
            unknown.code shouldBe "unknown_capability_token"
            unknown.category shouldBe "permission"

            val revoked = shouldThrow<CapabilityRegistryException> { registry.resolve("saf-root-1") }
            revoked.code shouldBe "unknown_capability_token"
            revoked.category shouldBe "permission"
        }

        test("given blank token or blank uri when registered then boundary rejects") {
            val registry = CapabilityRegistry()
            shouldThrow<IllegalArgumentException> {
                registry.register(token = "  ", treeUri = "content://x/tree/y")
            }
            shouldThrow<IllegalArgumentException> {
                registry.register(token = "tok", treeUri = "")
            }
        }
    }
}
