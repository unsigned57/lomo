package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: CapabilityRegistry.
 * - Owning layer: data Android capability edge.
 * - Priority tier: P0.
 * - Capability: bind rotating process capability tokens to a stable SAF workspace identity and a
 *   persisted tree URI without exposing URIs to Rust, and fail closed for invalid grants.
 *
 * Scenarios:
 * - Given a registered token and tree URI string, when resolved, then the same string is returned.
 * - Given an unknown token, when resolved, then a structured permission failure is returned.
 * - Given a revoked token, when resolved, then resolution fails as if the grant never existed.
 * - Given the same canonical SAF tree with different tokens, when grants are registered, then they
 *   carry the same stable workspace ID; a different tree carries a different ID.
 * - Given a blank/invalid token or blank URI, when registered, then construction fails closed.
 *
 * Observable outcomes:
 * - stable workspace ID equality/inequality, resolved URI, and structured registry failures.
 *
 * TDD proof:
 * - RED on 2026-07-27: registration returned Unit and exposed neither stableWorkspaceId nor a
 *   canonical SAF identity boundary.
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

        test("given rotating tokens when registered then stable identity follows the SAF tree") {
            val registry = CapabilityRegistry()
            val first =
                registry.register(
                    token = "saf-process-1",
                    treeUri = "content://com.lomo.documents/tree/primary%3ALomo",
                )
            val rotated =
                registry.register(
                    token = "saf-process-2",
                    treeUri = "content://com.lomo.documents/tree/primary%3aLomo",
                )
            val otherTree =
                registry.register(
                    token = "saf-process-3",
                    treeUri = "content://com.lomo.documents/tree/primary%3AOther",
                )

            first.stableWorkspaceId shouldBe rotated.stableWorkspaceId
            first.stableWorkspaceId shouldBe SafWorkspaceIdentity.fromTreeUri(first.treeUri)
            (first.stableWorkspaceId == otherTree.stableWorkspaceId) shouldBe false
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
