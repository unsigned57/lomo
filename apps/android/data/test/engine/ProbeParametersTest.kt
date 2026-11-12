package com.lomo.data.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: reject malformed/unbounded probe input before measurement; data owner; P0.
 * Scenarios: missing counts select documented defaults; valid boundary counts survive;
 * malformed, zero, negative and above-budget counts fail explicitly.
 * Observable outcomes: parsed count or IllegalArgumentException; no clamped/defaulted invalid input.
 * TDD proof: device measurement before/after and targeted host specs in performance continuation evidence.
 * Excludes: native operation cost and display timing.
 */
class ProbeParametersTest :
    FunSpec({
        test("given missing or valid count when parsed then preserve the documented value") {
            probeCount(null, 5, 512) shouldBe 5
            probeCount("1", 5, 512) shouldBe 1
            probeCount("512", 5, 512) shouldBe 512
        }
        test("given malformed or out of budget count when parsed then reject before measuring") {
            listOf("", "oops", "0", "-1", "513", "999999999999999999999").forEach { raw ->
                shouldThrow<IllegalArgumentException> { probeCount(raw, 5, 512) }
            }
        }
    })
