package com.lomo.data.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Behavior Contract:
 * Capability: caller-owned frozen retry identities for measured writes; data tooling owner; P0.
 * Scenarios: same logical write index reuses a token; different invocations/indices stay distinct;
 * a negative command index is rejected before reaching native.
 * Observable outcomes: valid MemoOperationId wire tokens and explicit rejection.
 * TDD proof: the history_seed device probe plus targeted host spec in performance continuation evidence.
 * Excludes: durable owner replay arbitration, covered by Rust operation_witness_contract.
 */
class ProbeWriteInvocationTest :
    FunSpec({
        test("given an invocation when retrying the same write then preserve its command identity") {
            val invocation = ProbeWriteInvocation.newInvocation("engine-history-seed")
            invocation.operationId(0) shouldBe invocation.operationId(0)
            invocation.operationId(1) shouldNotBe invocation.operationId(0)
            ProbeWriteInvocation.newInvocation(
                "engine-history-seed",
            ).operationId(0) shouldNotBe invocation.operationId(0)
        }
        test("given a negative write index when issuing a command then reject it") {
            shouldThrow<IllegalArgumentException> { ProbeWriteInvocation.newInvocation("probe").operationId(-1) }
        }
    })
