package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

// Behavior Contract:
// Capability: preserve the policy token during test maintenance.
// Scenarios: Given a policy, when its token is read, then the documented value is returned.
// Observable outcomes: returned token and checker exit status.
// TDD proof: Regression preservation - targeted policy tests pass before and after the storage refactor.
// Excludes: platform integration.
class PolicyTest : FunSpec({
    test("given a policy when read then its token is returned") {
        Policy().token() shouldBe "old"
    }
})
