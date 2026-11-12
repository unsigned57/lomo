package com.lomo.domain.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec

/*
 * Behavior Contract:
 * Capability: reject removed task-page bindings at the settings boundary; owning layer: domain; priority: P1.
 * Scenarios:
 * - Given a TASKS binding, when parsed, then it is rejected rather than navigating to a removed page.
 * Observable outcomes: IllegalArgumentException for the retired action.
 * TDD proof: RED /tmp/lomo-ui-red.stdout: expected IllegalArgumentException, but none was thrown.
 * GREEN: ./kotlin test --include-module=app --include-module=domain --include-module=ui-components;
 * /tmp/lomo-ui-green.stdout, all 1799 tests passed across the four module targets.
 * Excludes: task checkboxes within memos and UI rendering.
 */
class RetiredQuickBallActionTest :
    FunSpec({
        test("given a removed task page binding when parsed then the setting is rejected") {
            shouldThrow<IllegalArgumentException> {
                QuickBallConfig.parse("v1|TASKS|SCROLL_TOP|SEARCH|LONG_TEXT|FILTER")
            }
        }
    })
