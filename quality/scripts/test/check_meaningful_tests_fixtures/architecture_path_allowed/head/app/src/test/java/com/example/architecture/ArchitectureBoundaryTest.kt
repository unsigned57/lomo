package com.example.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.io.File

/*
 * Test Contract:
 * - Unit under test: BoundaryRules
 * - Owning layer: app
 * - Priority tier: P1
 *
 * Scenario matrix:
 * - Happy: the architecture file contains the boundary rule token.
 * - Boundary: the architecture test reads only the intended file.
 * - Failure: the assertion fails if the boundary token is removed.
 * - Must-not-happen: the boundary rule must not move unnoticed.
 *
 * Observable outcomes:
 * - architecture token visibility.
 *
 * Red phase:
 * - Not applicable - test-only coverage addition; no production change.
 *
 * Excludes:
 * - runtime business behavior.
 */
class ArchitectureBoundaryTest : FunSpec({
    test("allows architecture path checks") {
        val content = File("app/src/main/java/com/example/BoundaryRules.kt").readText()

        content shouldContain "rule"
    }
})
