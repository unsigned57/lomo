package com.lomo.sample

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * Capability: snapshot bytes retain workspace identity.
 * Scenarios: Given persisted JSON, when decoded as text, then workspace identity is present.
 * Observable outcomes: persisted workspace identifier.
 * TDD proof: Not applicable - test-only fixture; no production change within the declared scope.
 * Excludes: source architecture policies.
 */
class SnapshotStoreTest :
    FunSpec({
        test("given persisted JSON when read then identity remains") {
            val file = File("snapshot.json")
            file.readText().contains("workspace-1") shouldBe true
        }
    })
