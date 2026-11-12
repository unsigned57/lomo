package com.lomo.app.feature.trash

import com.lomo.ui.component.common.LomoListExitPhase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: empty trash admission after row exit; owner: app; priority: P1.
 * Scenarios:
 * - Given a removed last row with settled animation, when maintenance retains its hidden snapshot,
 *   then empty content is shown; a still-visible exit or source row keeps list content.
 * Observable outcomes: empty-state admission, independent of post-commit maintenance duration.
 * TDD proof: reports/manual/trash-index-picker/empty-red.log and trash-green.log.
 * Excludes: native delete durability, paging refresh latency and pixel rendering.
 */
class TrashContentStateTest :
    FunSpec({
        test("given no source rows and a hidden retained exit then trash is empty") {
            trashIsEmpty(0, listOf(LomoListExitPhase.Hidden)) shouldBe true
        }
        test("given a visible exit or source row then trash still has visible content") {
            trashIsEmpty(0, listOf(LomoListExitPhase.Exiting)) shouldBe false
            trashIsEmpty(0, listOf(null)) shouldBe false
            trashIsEmpty(1, listOf(LomoListExitPhase.Hidden)) shouldBe false
        }
        test("given no rows or retained exits then trash is empty") {
            trashIsEmpty(0, emptyList()) shouldBe true
        }
    })
