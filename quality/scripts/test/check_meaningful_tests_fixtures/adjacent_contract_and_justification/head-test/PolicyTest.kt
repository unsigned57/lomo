package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PolicyTest : FunSpec({
    test("given a policy when read then its token is returned") {
        Policy().token() shouldBe "new"
    }
})
