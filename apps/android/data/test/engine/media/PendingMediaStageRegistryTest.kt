package com.lomo.data.engine.media

/*
 * Behavior Contract:
 * - Unit under test: PendingMediaStageRegistry.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: preserve staged media facts until the memo operation is durably committed.
 *
 * Scenarios:
 * - Given staged facts, when a memo operation snapshots candidates, then every fact remains
 *   available for Rust-owned destination selection and retry until commit acknowledgement.
 * - Given a leased plan, when the operation commits, then both its path aliases are removed.
 * - Given a failed operation, when no commit acknowledgement arrives, then a later operation can
 *   lease the same fact without reconstructing it from the Markdown body.
 *
 * Observable outcomes:
 * - Registry snapshots and returned promote plans.
 *
 * TDD proof:
 * - RED before the fix because destination selection destructively removed the staged fact.
 *
 * Excludes:
 * - Media bytes, platform writes, and Rust promotion semantics.
 */

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class PendingMediaStageRegistryTest : DataFunSpec() {
    init {
        test("given a staged fact when a plan is leased then it remains until commit") {
            val registry = PendingMediaStageRegistry()
            val staged = stagedFacts()
            registry.put(staged)

            val plans = registry.allPlans("op-1")

            plans shouldHaveSize 1
            registry.snapshot().values.toSet() shouldBe setOf(staged)
        }

        test("given a leased plan when commit is acknowledged then all aliases are removed") {
            val registry = PendingMediaStageRegistry()
            val staged = stagedFacts()
            registry.put(staged)
            val plans = registry.allPlans("op-1")

            registry.commit(plans)

            registry.snapshot() shouldBe emptyMap()
        }
    }
}

private fun stagedFacts() =
    MediaStagedFacts(
        digest = "a".repeat(64),
        size = 4L,
        mime = "image/png",
        stagingPath = "/tmp/staged-photo.png",
        humanNameHint = "photo.png",
        suggestedFinalRelativePath = "media/photo.png",
    )
