package com.lomo.app.benchmark

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: benchmark write acknowledgement observes committed memo state; owner: app; P0.
 * Scenarios:
 * - Given a pending memo, when the harness looks for a committed card, then it cannot match.
 * - Given the same memo after commit, when the harness looks again, then the card matches.
 * Observable outcomes: eligibility under the actual benchmark card selector.
 * TDD proof: device profileGen RED recorded a committed write but failed its transient snackbar
 * acknowledgement and retried; GREEN: this spec and the device collection after state anchors.
 * Excludes: Rust transaction correctness and actual Compose pointer input.
 */
class BenchmarkMemoCommitAnchorTest :
    FunSpec({
        val committedCard = Regex("^benchmark_memo_card_.+$")

        test("given a pending create when awaiting a committed card then it cannot acknowledge the write") {
            committedCard.matches(BenchmarkAnchorContract.memoCard("memo-1", isPending = true)) shouldBe false
        }

        test("given a committed create when awaiting its card then it acknowledges the write") {
            committedCard.matches(BenchmarkAnchorContract.memoCard("memo-1", isPending = false)) shouldBe true
        }
    })
