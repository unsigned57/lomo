// architectural-boundary-check
package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.io.File

/*
 * Test Contract:
 * - Unit under test: LayerPolicy
 * - Owning layer: app
 * - Priority tier: P1
 *
 * Scenario matrix:
 * - Happy: the source exposes the boundary token.
 * - Boundary: the token lives in the expected layer file.
 * - Failure: the assertion fails if the boundary token is removed.
 * - Must-not-happen: the boundary contract must not be silently deleted.
 *
 * Observable outcomes:
 * - boundary token visibility.
 *
 * Red phase:
 * - Not applicable - test-only coverage addition; no production change.
 *
 * Excludes:
 * - runtime policy execution.
 */
class BoundaryMarkerTest : FunSpec({
    test("allows marked architecture boundary checks") {
        val content = File("app/src/main/java/com/example/LayerPolicy.kt").readText()

        content shouldContain "boundaryToken"
    }
})
