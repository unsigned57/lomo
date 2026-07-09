package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeUnifiedSyncProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: SyncProviderRegistry
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: expose one canonical unified sync provider per backend.
 *
 * Scenarios:
 * - Given one provider per backend, when the registry is queried, then the matching provider is returned.
 * - Given two providers claim the same backend, when the registry is built, then the duplicate provider graph is rejected.
 *
 * Observable outcomes:
 * - Registry lookup result and thrown configuration error.
 *
 * TDD proof:
 * - RED: before this fix duplicate backend providers are silently overwritten by associateBy.
 *
 * Excludes:
 * - Koin graph creation, repository implementation details, and provider sync behavior.
 */
class SyncProviderRegistryTest : DomainFunSpec() {
    init {
        test("given one provider per backend when queried then registry returns matching provider") {
            val gitProvider = FakeUnifiedSyncProvider(SyncBackendType.GIT)
            val inboxProvider = FakeUnifiedSyncProvider(SyncBackendType.INBOX)

            val registry = SyncProviderRegistry(setOf(gitProvider, inboxProvider))

            registry.get(SyncBackendType.GIT) shouldBe gitProvider
            registry.get(SyncBackendType.INBOX) shouldBe inboxProvider
            registry.active(SyncBackendType.NONE) shouldBe null
        }

        test("given duplicate backend providers when registry is built then provider graph is rejected") {
            val failure =
                shouldThrow<IllegalArgumentException> {
                    SyncProviderRegistry(
                        setOf(
                            FakeUnifiedSyncProvider(SyncBackendType.S3),
                            FakeUnifiedSyncProvider(SyncBackendType.S3),
                        ),
                    )
                }

            failure.message shouldBe "Duplicate unified sync providers for backends: S3"
        }
    }
}
